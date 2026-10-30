// SPDX-License-Identifier: Apache-2.0
package nl.syphernl.kiosk.chargelimit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Arguments: battery folder, switch file, on value, off value, mode, stop level, resume level. Mode "charge"
     * turns charging on. "limit" turns it on at or below the resume level and off at or above the stop level.
     * "start" does the same but also stops in between, because some switches cannot be read back and the
     * plugin has to know where it stands. Prints the level, what it did and the kernel's charging status.
     */
    static final String TICK = "cap=$(cat \"$1/capacity\") || exit 3; act=keep; "
        + "if [ \"$5\" = charge ] || [ \"$cap\" -le \"$7\" ]; then echo \"$3\" > \"$2\" || exit 4; act=charging; "
        + "elif [ \"$5\" = start ] || [ \"$cap\" -ge \"$6\" ]; then echo \"$4\" > \"$2\" || exit 4; act=paused; fi; "
        + "echo \"$cap $act $(cat \"$1/status\")\"";
    static final long INTERVAL_SECONDS = 60;
    static final int TIMEOUT_MS = 5000;
    static final String[] STOP_OPTIONS = percents(50, 100);
    static final String[] RESUME_OPTIONS = percents(20, 95);

    private final boolean schedule;
    private PluginHost host;
    private ScheduledExecutorService scheduler;
    private final Map<String, Object> settings = new LinkedHashMap<>();
    private final Set<String> broken = new HashSet<>();
    private boolean enabled;
    private int stopAt;
    private int resumeAt;
    private String[] control;
    /** Write "charge" once: the limit was turned off, starts off, or Stop charging at went up while paused. */
    private boolean charge;
    private boolean started;
    private boolean busy;
    /** Something changed while a command ran, so run again when it returns. */
    private boolean dirty;
    private int session;
    private Boolean paused;
    /** Counts requests for an early tick, so tests can see one without a scheduler. */
    int requested;

    public ChargeLimitPlugin() { this(true); }

    /** Tests drive tick themselves. */
    ChargeLimitPlugin(boolean schedule) { this.schedule = schedule; }

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        session++;
        busy = false;
        control = null;
        broken.clear();
        started = false;
        paused = null;
        stopAt = 0;
        resumeAt = 0;
        host.publishTextSensor("status", "Status", null);
        host.publishBinarySensor("paused", "Charging paused", "", null);
        configure(settings);
        // A session that starts with the limit off may follow one that left charging stopped.
        charge = !enabled;
        if (!schedule) return;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::tick, 2, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void configure(Map<String, Object> values) {
        settings.clear();
        settings.putAll(values);
        setEnabled(!Boolean.FALSE.equals(values.get("enabled")));
        int stop = level(values.get("stopAt"), 85);
        int resume = level(values.get("resumeAt"), 50);
        setLevels(stop, resume, resume != resumeAt && stop == stopAt);
        if (stop != stopAt || resume != resumeAt) save();
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
            setEnabled(Boolean.TRUE.equals(payload.get("on")));
        } else if ("select.stop_at".equals(event)) {
            setLevels(parse((String) payload.get("option")), resumeAt, false);
        } else if ("select.resume_at".equals(event)) {
            setLevels(stopAt, parse((String) payload.get("option")), true);
        } else if ("shizuku.state".equals(event)) {
            soon();
            return;
        } else {
            return;
        }
        save();
        publishControls();
        soon();
    }

    @Override
    public void stop() {
        // The host is already revoked, so the switch stays as it is. Turning the limit off first releases it.
        ScheduledExecutorService stopping;
        synchronized (this) {
            stopping = scheduler;
            scheduler = null;
            host = null;
            session++;
            busy = false;
        }
        if (stopping == null) return;
        stopping.shutdownNow();
        try {
            stopping.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Turning the limit off charges once, then leaves the switch to whatever else manages it. */
    private void setEnabled(boolean on) {
        if (enabled && !on) charge = true;
        if (on) charge = false;
        enabled = on;
        dirty = true;
    }

    /** The changed level wins: a higher resume level pushes stop up, a lower stop level pushes resume down. */
    private void setLevels(int stop, int resume, boolean resumeChanged) {
        if (resume >= stop) {
            if (resumeChanged) stop = Math.min(100, resume + 5);
            else resume = stop - 5;
        }
        // Topping up past the old limit should not wait for the battery to drain to the resume level.
        if (enabled && stop > stopAt && Boolean.TRUE.equals(paused)) charge = true;
        stopAt = stop;
        resumeAt = resume;
        dirty = true;
    }

    private void save() {
        settings.put("enabled", enabled);
        settings.put("stopAt", stopAt);
        settings.put("resumeAt", resumeAt);
        if (host != null) host.saveSettings(new LinkedHashMap<>(settings));
    }

    private void publishControls() {
        if (host == null) return;
        host.publishSwitch("limit", "Charge limit", enabled);
        host.publishSelect("stop_at", "Stop charging at", STOP_OPTIONS, stopAt + "%");
        host.publishSelect("resume_at", "Resume charging at", RESUME_OPTIONS, resumeAt + "%");
    }

    private void soon() {
        requested++;
        if (scheduler != null) scheduler.schedule(this::tick, 300, TimeUnit.MILLISECONDS);
    }

    synchronized void tick() {
        if (host == null || busy) return;
        dirty = false;
        if (!enabled && !charge) {
            report("Off, charges normally", false);
            return;
        }
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
        final String mode = control == null ? null : charge ? "charge" : started ? "limit" : "start";
        String[] command;
        if (mode == null) {
            List<String> args = new ArrayList<>();
            args.add(SH);
            args.add("-c");
            args.add(DETECT);
            args.add("sh");
            for (String[] candidate : CONTROLS) if (!broken.contains(candidate[0])) args.add(candidate[0]);
            command = args.toArray(new String[0]);
        } else {
            command = new String[] {SH, "-c", TICK, "sh", BATTERY, control[0], control[1], control[2], mode,
                String.valueOf(stopAt), String.valueOf(resumeAt)};
        }
        busy = true;
        final int current = session;
        try {
            host.executeShizuku(command, TIMEOUT_MS, (ok, data, error) -> done(current, mode, ok, data, error));
        } catch (RuntimeException e) {
            busy = false;
            report(e.getMessage(), true);
        }
    }

    private synchronized void done(int from, String mode, boolean ok, Object data, String error) {
        if (from != session || host == null) return;
        busy = false;
        try {
            handle(mode, ok, data, error);
        } finally {
            if (dirty) soon();
        }
    }

    private void handle(String mode, boolean ok, Object data, String error) {
        if (!ok) {
            report(error, true);
            return;
        }
        Map<?, ?> result = (Map<?, ?>) data;
        int exit = ((Number) result.get("exitCode")).intValue();
        String out = String.valueOf(result.get("stdout")).trim();
        if (mode == null) {
            control = exit == 0 ? control(out) : null;
            if (control == null) report("This device has no charging switch that works", true);
            else soon();
            return;
        }
        if (exit == 3) {
            report("Could not read the battery level", true);
            return;
        }
        if (exit == 4) {
            // Some kernels list a switch they cannot write. Try the next one.
            report("Could not write " + control[0], true);
            broken.add(control[0]);
            control = null;
            soon();
            return;
        }
        if (exit != 0) {
            report("Command failed with exit code " + exit + ": " + String.valueOf(result.get("stderr")).trim(), true);
            return;
        }
        Reading reading = Reading.parse(out);
        if (reading == null) {
            report("Unexpected output: " + out, true);
            return;
        }
        if ("charge".equals(mode)) charge = false;
        else started = true;
        if ("paused".equals(reading.action)) paused = true;
        else if ("charging".equals(reading.action)) paused = false;
        host.publishBinarySensor("paused", "Charging paused", "", enabled ? paused : Boolean.FALSE);
        report(describe(reading), false);
    }

    String describe(Reading reading) {
        if (!enabled) return "Off, charges normally (" + reading.level + "%)";
        if (Boolean.TRUE.equals(paused)) return "Paused at " + reading.level + "%, resumes at " + resumeAt + "%";
        return "Charging to " + stopAt + "% (" + reading.level + "%)";
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
