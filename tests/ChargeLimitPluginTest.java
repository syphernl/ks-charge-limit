// SPDX-License-Identifier: Apache-2.0
package nl.syphernl.kiosk.chargelimit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import me.jxl.kiosk.plugins.PluginHost;

public final class ChargeLimitPluginTest {
    static final class Host implements PluginHost {
        final Map<String, Object> shizuku = new HashMap<>();
        final List<String[]> commands = new ArrayList<>();
        final Map<String, Object> selects = new HashMap<>();
        final Map<String, Boolean> switches = new HashMap<>();
        final Map<String, Boolean> binaries = new HashMap<>();
        Map<String, Object> saved;
        PluginHost.CommandCallback pending;
        String status;
        boolean statusError;

        Host() {
            shizuku.put("status", "ready");
            shizuku.put("uid", 0);
        }
        public Map<String, Object> shizukuState() { return new HashMap<>(shizuku); }
        public void executeShizuku(String[] command, int timeoutMs, PluginHost.CommandCallback callback) {
            commands.add(command);
            pending = callback;
        }
        public void publishSwitch(String key, String name, boolean state) { switches.put(key, state); }
        public void publishSelect(String key, String name, String[] options, String state) { selects.put(key, state); }
        public void publishTextSensor(String key, String name, String state) {}
        public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) { binaries.put(key, state); }
        public void saveSettings(Map<String, Object> values) { saved = values; }
        public void status(String message, boolean error) { status = message; statusError = error; }
        public void showWindow(String title, String message, String buttonLabel) {}
        public void hideWindow() {}
        public void log(String message) {}

        void answer(int exitCode, String stdout) {
            Map<String, Object> result = new HashMap<>();
            result.put("exitCode", exitCode);
            result.put("stdout", stdout);
            result.put("stderr", "");
            PluginHost.CommandCallback callback = pending;
            pending = null;
            callback.onResult(true, result, null);
        }
        String[] last() { return commands.get(commands.size() - 1); }
        String mode() { return last()[8]; }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Map<String, Object> settings(boolean enabled, int stop, int resume) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", enabled);
        settings.put("stopAt", stop);
        settings.put("resumeAt", resume);
        return settings;
    }

    /** Started, with the Lenovo switch detected and the first tick answered at the given level. */
    static ChargeLimitPlugin started(Host host, Map<String, Object> settings, String firstAnswer) {
        ChargeLimitPlugin plugin = new ChargeLimitPlugin(false);
        plugin.start(host, settings);
        plugin.tick();
        host.answer(0, ChargeLimitPlugin.BATTERY + "/charging_enable\n");
        plugin.tick();
        host.answer(0, firstAnswer);
        return plugin;
    }

    public static void main(String[] args) throws Exception {
        tickScriptStopsAndResumes();
        detectScriptPicksTheFirstFile();
        firstTickPausesInBetween();
        pausesAtTheLimit();
        selectsKeepResumeBelowStop();
        settingsPageRaisesStopAndSaves();
        raisingStopWhilePausedChargesAgain();
        switchingOffReleasesOnce();
        startingOffReleasesOnce();
        changeDuringATickRunsAgain();
        unwritableSwitchTriesTheNext();
        staleCallbackIsIgnored();
        refusesShizukuWithoutRoot();
        System.out.println("ChargeLimitPluginTest passed");
    }

    /** Runs the real TICK script against a fake battery folder. */
    static String tick(File dir, int level, String mode) throws Exception {
        Files.write(new File(dir, "capacity").toPath(), (level + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "status").toPath(), "Cmd discharging\n".getBytes(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder("/bin/sh", "-c", ChargeLimitPlugin.TICK, "sh", dir.getPath(),
            new File(dir, "switch").getPath(), "1", "0", mode, "85", "50").start();
        String out = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
        check(process.waitFor() == 0, "tick script failed: " + out);
        return out;
    }

    static String switchValue(File dir) throws Exception {
        File file = new File(dir, "switch");
        return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim() : "";
    }

    static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    static void tickScriptStopsAndResumes() throws Exception {
        File dir = Files.createTempDirectory("battery").toFile();
        check(tick(dir, 70, "limit").equals("70 keep Cmd discharging"), "limit keeps in between");
        check(switchValue(dir).isEmpty(), "limit wrote in between");
        check(tick(dir, 85, "limit").equals("85 paused Cmd discharging"), "limit pauses at stop");
        check(switchValue(dir).equals("0"), "pause wrote " + switchValue(dir));
        check(tick(dir, 50, "limit").equals("50 charging Cmd discharging"), "limit charges at resume");
        check(switchValue(dir).equals("1"), "charge wrote " + switchValue(dir));
        check(tick(dir, 70, "start").equals("70 paused Cmd discharging"), "start pauses in between");
        check(switchValue(dir).equals("0"), "start wrote " + switchValue(dir));
        check(tick(dir, 40, "start").equals("40 charging Cmd discharging"), "start charges below resume");
        tick(dir, 90, "limit");
        check(tick(dir, 90, "charge").equals("90 charging Cmd discharging"), "charge always charges");
        check(switchValue(dir).equals("1"), "charge mode wrote " + switchValue(dir));
    }

    static void detectScriptPicksTheFirstFile() throws Exception {
        File dir = Files.createTempDirectory("sysfs").toFile();
        File second = new File(dir, "b");
        check(second.createNewFile(), "could not create " + second);
        Process process = new ProcessBuilder("/bin/sh", "-c", ChargeLimitPlugin.DETECT, "sh",
            new File(dir, "a").getPath(), second.getPath()).start();
        String out = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
        check(process.waitFor() == 0, "detect failed");
        check(out.equals(second.getPath()), "detect picked " + out);
    }

    static void firstTickPausesInBetween() {
        Host host = new Host();
        started(host, settings(true, 85, 50), "70 paused Discharging\n");
        check(host.mode().equals("start"), "first tick mode " + host.mode());
        check(Boolean.TRUE.equals(host.binaries.get("paused")), "paused unknown after the first tick");
    }

    static void pausesAtTheLimit() {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 85, 50), "60 paused Discharging\n");
        plugin.tick();
        String[] command = host.last();
        check(command[5].equals(ChargeLimitPlugin.BATTERY + "/charging_enable"), "switch " + command[5]);
        check(host.mode().equals("limit") && command[9].equals("85") && command[10].equals("50"), "limit arguments");
        host.answer(0, "90 paused Discharging\n");
        check(Boolean.TRUE.equals(host.binaries.get("paused")), "not paused");
        check(host.status.equals("Paused at 90%, resumes at 50%"), host.status);
        check(!host.statusError, "error status");
        plugin.tick();
        host.answer(0, "70 keep Discharging\n");
        check(Boolean.TRUE.equals(host.binaries.get("paused")), "keep lost the pause");
    }

    static void selectsKeepResumeBelowStop() throws Exception {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 80, 50), "60 charging Charging\n");
        plugin.onEvent("select.resume_at", Collections.singletonMap("option", "80%"));
        check("85%".equals(host.selects.get("stop_at")), "stop " + host.selects.get("stop_at"));
        check("80%".equals(host.selects.get("resume_at")), "resume " + host.selects.get("resume_at"));
        check(Integer.valueOf(85).equals(host.saved.get("stopAt")), "saved stop " + host.saved.get("stopAt"));
        plugin.onEvent("select.stop_at", Collections.singletonMap("option", "60%"));
        check("55%".equals(host.selects.get("resume_at")), "resume " + host.selects.get("resume_at"));
        check(Integer.valueOf(55).equals(host.saved.get("resumeAt")), "saved resume " + host.saved.get("resumeAt"));
    }

    static void settingsPageRaisesStopAndSaves() {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 85, 50), "60 charging Charging\n");
        plugin.configure(settings(true, 85, 90));
        check("95%".equals(host.selects.get("stop_at")), "stop " + host.selects.get("stop_at"));
        check("90%".equals(host.selects.get("resume_at")), "resume " + host.selects.get("resume_at"));
        check(Integer.valueOf(95).equals(host.saved.get("stopAt")), "clamped stop not saved");
    }

    static void raisingStopWhilePausedChargesAgain() throws Exception {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 85, 50), "85 paused Discharging\n");
        plugin.onEvent("select.stop_at", Collections.singletonMap("option", "100%"));
        plugin.tick();
        check(host.mode().equals("charge"), "raise did not charge: " + host.mode());
        host.answer(0, "85 charging Charging\n");
        check(host.status.equals("Charging to 100% (85%)"), host.status);
        plugin.tick();
        check(host.mode().equals("limit"), "charge repeated");
    }

    static void switchingOffReleasesOnce() throws Exception {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 85, 50), "90 paused Discharging\n");
        plugin.onEvent("switch.limit", Collections.singletonMap("on", false));
        check(Boolean.FALSE.equals(host.switches.get("limit")), "switch still on");
        check(Boolean.FALSE.equals(host.saved.get("enabled")), "off not saved");
        plugin.tick();
        check(host.mode().equals("charge"), "no release: " + host.mode());
        host.answer(0, "90 charging Charging\n");
        check(host.status.startsWith("Off"), host.status);
        check(Boolean.FALSE.equals(host.binaries.get("paused")), "still paused");
        int count = host.commands.size();
        plugin.tick();
        check(host.commands.size() == count, "wrote again while off");
    }

    static void startingOffReleasesOnce() {
        Host host = new Host();
        ChargeLimitPlugin plugin = new ChargeLimitPlugin(false);
        plugin.start(host, settings(false, 85, 50));
        plugin.tick();
        host.answer(0, ChargeLimitPlugin.BATTERY + "/charging_enable\n");
        plugin.tick();
        check(host.mode().equals("charge"), "start while off did not release: " + host.mode());
        host.answer(0, "70 charging Charging\n");
        int count = host.commands.size();
        plugin.tick();
        check(host.commands.size() == count, "wrote again while off");
    }

    static void changeDuringATickRunsAgain() throws Exception {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 85, 50), "70 paused Discharging\n");
        plugin.tick();
        plugin.onEvent("switch.limit", Collections.singletonMap("on", false));
        plugin.tick();
        check(host.pending != null && host.mode().equals("limit"), "busy tick ran another command");
        int before = plugin.requested;
        host.answer(0, "70 keep Discharging\n");
        check(plugin.requested > before, "no rerun after the busy tick");
        plugin.tick();
        check(host.mode().equals("charge"), "release waited for the next minute: " + host.mode());
    }

    static void unwritableSwitchTriesTheNext() {
        Host host = new Host();
        ChargeLimitPlugin plugin = new ChargeLimitPlugin(false);
        plugin.start(host, settings(true, 85, 50));
        plugin.tick();
        host.answer(0, ChargeLimitPlugin.BATTERY + "/charging_enabled\n");
        plugin.tick();
        host.answer(4, "");
        check(host.statusError, "write failure not reported");
        plugin.tick();
        check(!Arrays.asList(host.last()).contains(ChargeLimitPlugin.BATTERY + "/charging_enabled"), "broken switch tried again");
    }

    static void staleCallbackIsIgnored() {
        Host host = new Host();
        ChargeLimitPlugin plugin = new ChargeLimitPlugin(false);
        plugin.start(host, settings(true, 85, 50));
        plugin.tick();
        PluginHost.CommandCallback old = host.pending;
        plugin.stop();
        plugin.start(host, settings(true, 85, 50));
        Map<String, Object> result = new HashMap<>();
        result.put("exitCode", 0);
        result.put("stdout", ChargeLimitPlugin.BATTERY + "/charging_enable\n");
        old.onResult(true, result, null);
        plugin.tick();
        check(host.last()[2].equals(ChargeLimitPlugin.DETECT), "stale detection was applied");
    }

    static void refusesShizukuWithoutRoot() {
        Host host = new Host();
        host.shizuku.put("uid", 2000);
        ChargeLimitPlugin plugin = new ChargeLimitPlugin(false);
        plugin.start(host, settings(true, 85, 50));
        plugin.tick();
        check(host.commands.isEmpty(), "ran without root");
        check(host.statusError, "no error status");
        plugin.stop();
    }
}
