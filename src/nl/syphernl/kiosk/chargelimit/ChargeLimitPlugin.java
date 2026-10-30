// SPDX-License-Identifier: Apache-2.0
package nl.syphernl.kiosk.chargelimit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Once a minute, reads the battery level and flips the kernel's charging
 * switch through Shizuku: off at Stop charging at, on again at Resume charging
 * at. In between it leaves the switch alone, so the level drifts down from the
 * upper limit to the lower one before charging starts again. Writing a sysfs
 * node needs root, so Shizuku must run as uid 0.
 */
public final class ChargeLimitPlugin implements KioskPlugin {
    static final String BATTERY = "/sys/class/power_supply/battery";
    /** Known switches: the file, the value that lets the battery charge and the value that stops it. */
    static final String[][] CONTROLS = {
        {BATTERY + "/device/Charging_Enable", "1", "0"}, // Amazon Fire HD 8 (karnak)
        {BATTERY + "/charging_enable", "1", "0"}, // Lenovo Tab M10 FHD Plus (TB-X606F)
        {BATTERY + "/charging_enabled", "1", "0"},
        {BATTERY + "/battery_charging_enabled", "1", "0"},
        {BATTERY + "/input_suspend", "0", "1"},
        {BATTERY + "/store_mode", "0", "1"},
    };
    static final String SH = "/system/bin/sh";
    static final String DETECT = "for f in \"$@\"; do [ -e \"$f\" ] && { echo \"$f\"; exit 0; }; done; exit 1";
    /**
     * Arguments: battery folder, switch file, on value, off value, mode (limit or release), stop level, resume
     * level. Prints the level, what it did and the kernel's charging status.
     */
    static final String TICK = "cap=$(cat \"$1/capacity\") || exit 3; act=keep; "
        + "if [ \"$5\" = release ] || [ \"$cap\" -le \"$7\" ]; then echo \"$3\" > \"$2\" || exit 4; act=charging; "
        + "elif [ \"$cap\" -ge \"$6\" ]; then echo \"$4\" > \"$2\" || exit 4; act=paused; fi; "
        + "echo \"$cap $act $(cat \"$1/status\")\"";
    static final long INTERVAL_SECONDS = 60;
    static final int TIMEOUT_MS = 5000;
    static final String[] STOP_OPTIONS = percents(50, 100);
    static final String[] RESUME_OPTIONS = percents(20, 95);

    private final boolean schedule;
    private PluginHost host;
    private ScheduledExecutorService scheduler;
    private final Map<String, Object> settings = new LinkedHashMap<>();
    private boolean enabled;
    private int stopAt;
    private int resumeAt;
    private String[] control;
    private boolean release;
    private boolean busy;
    private Boolean paused;

    public ChargeLimitPlugin() { this(true); }

    /** Tests drive tick themselves. */
    ChargeLimitPlugin(boolean schedule) { this.schedule = schedule; }

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        control = null;
        release = false;
        paused = null;
        host.publishTextSensor("status", "Status", null);
        host.publishBinarySensor("paused", "Charging paused", "", null);
        configure(settings);
        if (!enabled) report("Off, charges normally", false);
        if (!schedule) return;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::tick, 2, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void configure(Map<String, Object> values) {
        boolean was = enabled;
        settings.clear();
        settings.putAll(values);
        enabled = !Boolean.FALSE.equals(values.get("enabled"));
        stopAt = level(values.get("stopAt"), 85);
        resumeAt = level(values.get("resumeAt"), 50);
        if (resumeAt >= stopAt) resumeAt = stopAt - 5;
        // Turning the limit off charges once, then leaves the switch to whatever else manages it.
        if (was && !enabled) release = true;
        if (enabled) release = false;
        publishControls();
        soon();
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if ("switch.limit".equals(event)) {
            boolean on = Boolean.TRUE.equals(payload.get("on"));
            release = enabled && !on;
            enabled = on;
            settings.put("enabled", on);
        } else if ("select.stop_at".equals(event)) {
            stopAt = parse((String) payload.get("option"));
            if (resumeAt >= stopAt) resumeAt = stopAt - 5;
        } else if ("select.resume_at".equals(event)) {
            resumeAt = parse((String) payload.get("option"));
            if (resumeAt >= stopAt) stopAt = resumeAt + 5;
        } else if (!"shizuku.state".equals(event)) {
            return;
        }
        if (!"shizuku.state".equals(event)) {
            settings.put("stopAt", stopAt);
            settings.put("resumeAt", resumeAt);
            host.saveSettings(new LinkedHashMap<>(settings));
            publishControls();
        }
        soon();
    }

    @Override
    public synchronized void stop() {
        // The host is already revoked, so the switch stays as it is. Turning the limit off first releases it.
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
        host = null;
    }

    private void publishControls() {
        if (host == null) return;
        host.publishSwitch("limit", "Charge limit", enabled);
        host.publishSelect("stop_at", "Stop charging at", STOP_OPTIONS, stopAt + "%");
        host.publishSelect("resume_at", "Resume charging at", RESUME_OPTIONS, resumeAt + "%");
    }

    private void soon() {
        if (scheduler != null) scheduler.schedule(this::tick, 300, TimeUnit.MILLISECONDS);
    }

    synchronized void tick() {
        if (host == null || busy) return;
        if (!enabled && !release) return;
        Map<String, Object> state = host.shizukuState();
        if (!"ready".equals(state.get("status"))) {
            report("No Shizuku access for Kiosk Satellite", true);
            return;
        }
        Object uid = state.get("uid");
        if (!(uid instanceof Number) || ((Number) uid).intValue() != 0) {
            report("Shizuku runs without root, so it cannot switch charging", true);
            return;
        }
        String[] command;
        if (control == null) {
            command = new String[CONTROLS.length + 4];
            command[0] = SH;
            command[1] = "-c";
            command[2] = DETECT;
            command[3] = "sh";
            for (int i = 0; i < CONTROLS.length; i++) command[i + 4] = CONTROLS[i][0];
        } else {
            command = new String[] {SH, "-c", TICK, "sh", BATTERY, control[0], control[1], control[2],
                release ? "release" : "limit", String.valueOf(stopAt), String.valueOf(resumeAt)};
        }
        busy = true;
        final boolean detecting = control == null;
        final boolean releasing = release;
        try {
            host.executeShizuku(command, TIMEOUT_MS, (ok, data, error) -> done(detecting, releasing, ok, data, error));
        } catch (RuntimeException e) {
            busy = false;
            report(e.getMessage(), true);
        }
    }

    private synchronized void done(boolean detecting, boolean releasing, boolean ok, Object data, String error) {
        busy = false;
        if (host == null) return;
        if (!ok) {
            report(error, true);
            return;
        }
        Map<?, ?> result = (Map<?, ?>) data;
        int exit = ((Number) result.get("exitCode")).intValue();
        String out = String.valueOf(result.get("stdout")).trim();
        if (detecting) {
            control = exit == 0 ? control(out) : null;
            if (control == null) report("This device has no known charging switch", true);
            else soon();
            return;
        }
        if (exit != 0) {
            report(exit == 4 ? "Could not write " + control[0] : "Could not read the battery level", true);
            return;
        }
        if (releasing) release = false;
        Reading reading = Reading.parse(out);
        if (reading == null) {
            report("Unexpected output: " + out, true);
            return;
        }
        if ("paused".equals(reading.action)) paused = true;
        else if ("charging".equals(reading.action)) paused = false;
        host.publishBinarySensor("paused", "Charging paused", "", releasing ? Boolean.FALSE : paused);
        report(describe(reading, releasing), false);
    }

    String describe(Reading reading, boolean releasing) {
        if (releasing || !enabled) return "Off, charges normally (" + reading.level + "%)";
        if (Boolean.TRUE.equals(paused)) return "Paused at " + reading.level + "%, resumes at " + resumeAt + "%";
        if (Boolean.FALSE.equals(paused)) return "Charging to " + stopAt + "% (" + reading.level + "%)";
        return reading.level + "%, " + reading.status;
    }

    private void report(String message, boolean error) {
        if (host == null) return;
        host.status(message, error);
        host.publishTextSensor("status", "Status", message);
    }

    static String[] control(String path) {
        for (String[] candidate : CONTROLS) if (candidate[0].equals(path)) return candidate;
        return null;
    }

    static int level(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    static int parse(String option) {
        return Integer.parseInt(option.replace("%", "").trim());
    }

    static String[] percents(int from, int to) {
        String[] options = new String[(to - from) / 5 + 1];
        for (int i = 0; i < options.length; i++) options[i] = (from + i * 5) + "%";
        return options;
    }

    static final class Reading {
        final int level;
        final String action;
        final String status;

        Reading(int level, String action, String status) {
            this.level = level;
            this.action = action;
            this.status = status;
        }

        /** "85 paused Discharging". The status can hold spaces, such as "Cmd discharging" on a Fire. */
        static Reading parse(String out) {
            String[] parts = out.split(" ", 3);
            if (parts.length < 2) return null;
            try {
                return new Reading(Integer.parseInt(parts[0]), parts[1], parts.length > 2 ? parts[2] : "");
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
