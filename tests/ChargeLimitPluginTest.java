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
            PluginHost.CommandCallback callback = pending;
            pending = null;
            callback.onResult(true, result, null);
        }
        String[] last() { return commands.get(commands.size() - 1); }
    }

    static Map<String, Object> settings(boolean enabled, int stop, int resume) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", enabled);
        settings.put("stopAt", stop);
        settings.put("resumeAt", resume);
        return settings;
    }

    static ChargeLimitPlugin started(Host host, Map<String, Object> settings) {
        ChargeLimitPlugin plugin = new ChargeLimitPlugin(false);
        plugin.start(host, settings);
        plugin.tick();
        host.answer(0, ChargeLimitPlugin.BATTERY + "/charging_enable\n");
        return plugin;
    }

    public static void main(String[] args) throws Exception {
        tickScriptStopsAndResumes();
        detectScriptPicksTheFirstFile();
        pausesAtTheLimit();
        selectsKeepResumeBelowStop();
        switchingOffReleasesOnce();
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
        assert process.waitFor() == 0 : out;
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
        assert tick(dir, 70, "limit").equals("70 keep Cmd discharging");
        assert switchValue(dir).isEmpty();
        assert tick(dir, 85, "limit").equals("85 paused Cmd discharging");
        assert switchValue(dir).equals("0");
        assert tick(dir, 50, "limit").equals("50 charging Cmd discharging");
        assert switchValue(dir).equals("1");
        tick(dir, 90, "limit");
        assert tick(dir, 90, "release").equals("90 charging Cmd discharging");
        assert switchValue(dir).equals("1");
    }

    static void detectScriptPicksTheFirstFile() throws Exception {
        File dir = Files.createTempDirectory("sysfs").toFile();
        File second = new File(dir, "b");
        assert second.createNewFile();
        Process process = new ProcessBuilder("/bin/sh", "-c", ChargeLimitPlugin.DETECT, "sh",
            new File(dir, "a").getPath(), second.getPath()).start();
        String out = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
        assert process.waitFor() == 0;
        assert out.equals(second.getPath());
    }

    static void pausesAtTheLimit() {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 85, 50));
        plugin.tick();
        String[] command = host.last();
        assert command[5].equals(ChargeLimitPlugin.BATTERY + "/charging_enable");
        assert command[8].equals("limit") && command[9].equals("85") && command[10].equals("50");
        host.answer(0, "90 paused Discharging\n");
        assert Boolean.TRUE.equals(host.binaries.get("paused"));
        assert host.status.equals("Paused at 90%, resumes at 50%") : host.status;
        assert !host.statusError;
        plugin.tick();
        host.answer(0, "70 keep Discharging\n");
        assert Boolean.TRUE.equals(host.binaries.get("paused"));
    }

    static void selectsKeepResumeBelowStop() throws Exception {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 80, 50));
        plugin.onEvent("select.resume_at", Collections.singletonMap("option", "80%"));
        assert "85%".equals(host.selects.get("stop_at"));
        assert "80%".equals(host.selects.get("resume_at"));
        assert Integer.valueOf(85).equals(host.saved.get("stopAt"));
        plugin.onEvent("select.stop_at", Collections.singletonMap("option", "60%"));
        assert "55%".equals(host.selects.get("resume_at"));
        assert Integer.valueOf(55).equals(host.saved.get("resumeAt"));
    }

    static void switchingOffReleasesOnce() throws Exception {
        Host host = new Host();
        ChargeLimitPlugin plugin = started(host, settings(true, 85, 50));
        plugin.onEvent("switch.limit", Collections.singletonMap("on", false));
        assert Boolean.FALSE.equals(host.switches.get("limit"));
        assert Boolean.FALSE.equals(host.saved.get("enabled"));
        plugin.tick();
        assert host.last()[8].equals("release");
        host.answer(0, "90 charging Charging\n");
        assert host.status.startsWith("Off") : host.status;
        int count = host.commands.size();
        plugin.tick();
        assert host.commands.size() == count;
    }

    static void refusesShizukuWithoutRoot() {
        Host host = new Host();
        host.shizuku.put("uid", 2000);
        ChargeLimitPlugin plugin = new ChargeLimitPlugin(false);
        plugin.start(host, settings(true, 85, 50));
        plugin.tick();
        assert host.commands.isEmpty();
        assert host.statusError;
        plugin.stop();
    }
}
