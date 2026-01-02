package org.linuxfirmware.consolePlus;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;

public class ShellCommand implements CommandExecutor, TabCompleter {

    private static final Map<Integer, String> ANSI_TO_MC = new HashMap<>();
    static {
        ANSI_TO_MC.put(0, "§r"); ANSI_TO_MC.put(1, "§l");
        ANSI_TO_MC.put(30, "§0"); ANSI_TO_MC.put(31, "§c"); ANSI_TO_MC.put(32, "§a"); ANSI_TO_MC.put(33, "§e");
        ANSI_TO_MC.put(34, "§9"); ANSI_TO_MC.put(35, "§5"); ANSI_TO_MC.put(36, "§b"); ANSI_TO_MC.put(37, "§7");
        ANSI_TO_MC.put(90, "§8"); ANSI_TO_MC.put(91, "§c"); ANSI_TO_MC.put(92, "§a"); ANSI_TO_MC.put(93, "§e");
        ANSI_TO_MC.put(94, "§9"); ANSI_TO_MC.put(95, "§d"); ANSI_TO_MC.put(96, "§b"); ANSI_TO_MC.put(97, "§f");
    }

    private final ConsolePlus plugin;
    private final Map<Integer, ManagedProcess> activeProcesses = new ConcurrentHashMap<>();
    private final Map<String, List<String>> environments = new HashMap<>();
    private final File envFile;
    private final boolean isWindows;
    private String selectedEnv = "default";

    public ShellCommand(ConsolePlus plugin) {
        this.plugin = plugin;
        this.envFile = new File(plugin.getDataFolder(), "environments.yml");
        this.isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        loadEnvironments();
    }

    private String msg(String key) {
        return plugin.getI18n().get(key);
    }

    private String msg(String key, String p1, Object v1) {
        Map<String, Object> map = new HashMap<>();
        map.put(p1, v1);
        return plugin.getI18n().get(key, map);
    }

    private String msg(String key, String p1, Object v1, String p2, Object v2) {
        Map<String, Object> map = new HashMap<>();
        map.put(p1, v1);
        map.put(p2, v2);
        return plugin.getI18n().get(key, map);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filterStrings(Arrays.asList("run", "input", "list", "stop", "env", "help"), args[0]);
        }

        if (args.length >= 2) {
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "stop":
                case "input":
                    if (args.length == 2) {
                        return activeProcesses.keySet().stream().map(String::valueOf).collect(Collectors.toList());
                    }
                    break;
                case "run":
                    if (args[args.length - 2].equals("-e")) {
                        return filterStrings(new ArrayList<>(environments.keySet()), args[args.length - 1]);
                    }
                    if (args[args.length - 2].equals("-t") || args[args.length - 2].equals("-d")) {
                        return Collections.emptyList();
                    }
                    return filterStrings(Arrays.asList("-d", "-e", "-t"), args[args.length - 1]);
                case "env":
                    if (args.length == 2) {
                        return filterStrings(Arrays.asList("create", "select", "delete", "edit", "list"), args[1]);
                    }
                    if (args.length == 3) {
                        return filterStrings(new ArrayList<>(environments.keySet()), args[2]);
                    }
                    break;
            }
        }
        return Collections.emptyList();
    }

    private List<String> filterStrings(List<String> list, String input) {
        return list.stream().filter(s -> s.toLowerCase().startsWith(input.toLowerCase())).collect(Collectors.toList());
    }

    private void loadEnvironments() {
        environments.put("default", new ArrayList<>());
        if (!envFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(envFile);
        for (String key : config.getKeys(false)) {
            if (!key.equalsIgnoreCase("default")) {
                environments.put(key, config.getStringList(key));
            }
        }
    }

    private void saveEnvironments() {
        YamlConfiguration config = new YamlConfiguration();
        environments.forEach((name, lines) -> {
            if (!name.equalsIgnoreCase("default")) {
                config.set(name, lines);
            }
        });
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            config.save(envFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save environments: " + e.getMessage());
        }
    }

    private int getNextId() {
        int id = 1;
        while (activeProcesses.containsKey(id)) {
            id++;
        }
        return id;
    }

    private static class ManagedProcess {
        Process process;
        final String command;
        final long startTime;
        BufferedWriter writer;
        
        long lastSampleTime = 0;
        long lastCpuNanos = 0;
        double lastUsage = 0.0;

        ManagedProcess(Process process, String command, Charset charset) {
            this.process = process;
            this.command = command;
            this.startTime = System.currentTimeMillis();
            updateProcess(process, charset);
        }

        void updateProcess(Process process, Charset charset) {
            this.process = process;
            this.writer = (process != null) ? new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), charset)) : null;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(msg("error-prefix") + msg("access-denied"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "run":
                handleRun(sender, args);
                break;
            case "list":
                listProcesses(sender);
                break;
            case "stop":
                handleStop(sender, args);
                break;
            case "input":
                handleInput(sender, args);
                break;
            case "env":
                handleEnv(sender, args);
                break;
            case "help":
            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void handleRun(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("error-prefix") + "Usage: /shell run [-d dir] [-e env] [-t timeout] <command>");
            return;
        }

        String workDir = null;
        String envName = selectedEnv;
        Integer customTimeout = null;
        int i = 1;
        while (i < args.length) {
            if (args[i].equals("-d") && i + 1 < args.length) {
                workDir = args[i + 1];
                i += 2;
            } else if (args[i].equals("-e") && i + 1 < args.length) {
                envName = args[i + 1];
                i += 2;
            } else if (args[i].equals("-t") && i + 1 < args.length) {
                try {
                    customTimeout = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(msg("error-prefix") + msg("invalid-id"));
                }
                i += 2;
            } else {
                break;
            }
        }

        if (i >= args.length) {
            sender.sendMessage(msg("error-prefix") + msg("no-command-specified"));
            return;
        }

        if (envName != null && !environments.containsKey(envName)) {
            sender.sendMessage(msg("warn-prefix") + msg("env-fallback", "name", envName));
            envName = "default";
        }
        if (envName == null) envName = "default";

        String[] cmdArgs = new String[args.length - i];
        System.arraycopy(args, i, cmdArgs, 0, args.length - i);
        String fullCommand = String.join(" ", cmdArgs);
        executeAsync(fullCommand, (ConsoleCommandSender) sender, workDir, envName, customTimeout);
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("error-prefix") + "Usage: /shell stop <id>");
            return;
        }
        try {
            int id = Integer.parseInt(args[1]);
            stopProcess(sender, id);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg("error-prefix") + msg("invalid-id"));
        }
    }

    private void handleInput(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("error-prefix") + "Usage: /shell input <id> <text>");
            return;
        }
        try {
            int id = Integer.parseInt(args[1]);
            String input = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            ManagedProcess mp = activeProcesses.get(id);
            if (mp != null && mp.writer != null) {
                mp.writer.write(input);
                mp.writer.newLine();
                mp.writer.flush();
                sender.sendMessage(msg("prefix") + msg("input-sent", "id", id));
            } else {
                sender.sendMessage(msg("error-prefix") + msg("process-not-found"));
            }
        } catch (Exception e) {
            sender.sendMessage(msg("error-prefix") + "Error: " + e.getMessage());
        }
    }

    private void handleEnv(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("prefix") + "Env Usage: /shell env <create|select|delete|edit|list>");
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "create":
                if (args.length < 3) { sender.sendMessage(msg("error-prefix") + "/shell env create <name>"); return; }
                if (args[2].equalsIgnoreCase("default")) { sender.sendMessage(msg("error-prefix") + msg("env-reserved")); return; }
                environments.putIfAbsent(args[2], new ArrayList<>());
                saveEnvironments();
                sender.sendMessage(msg("prefix") + msg("env-created", "name", args[2]));
                break;
            case "select":
                if (args.length < 3) { sender.sendMessage(msg("error-prefix") + "/shell env select <name>"); return; }
                if (!environments.containsKey(args[2])) {
                    sender.sendMessage(msg("warn-prefix") + msg("env-fallback", "name", args[2]));
                    selectedEnv = "default";
                } else {
                    selectedEnv = args[2];
                    sender.sendMessage(msg("prefix") + msg("env-selected", "name", selectedEnv));
                }
                break;
            case "delete":
                if (args.length < 3) { sender.sendMessage(msg("error-prefix") + "/shell env delete <name>"); return; }
                if (args[2].equalsIgnoreCase("default")) { sender.sendMessage(msg("error-prefix") + msg("env-delete-default")); return; }
                environments.remove(args[2]);
                if (args[2].equals(selectedEnv)) {
                    selectedEnv = "default";
                    sender.sendMessage(msg("warn-prefix") + msg("env-active-deleted"));
                }
                saveEnvironments();
                sender.sendMessage(msg("prefix") + msg("env-deleted"));
                break;
            case "list":
                if (args.length < 3) {
                    sender.sendMessage(msg("prefix") + msg("list-env-header"));
                    environments.forEach((name, lines) -> {
                        String prefix = name.equals(selectedEnv) ? "§6* " : "§f- ";
                        sender.sendMessage(prefix + name + " §7(" + lines.size() + " lines)");
                    });
                    sender.sendMessage(msg("list-env-usage"));
                } else {
                    List<String> envLines = environments.get(args[2]);
                    if (envLines == null) { sender.sendMessage(msg("error-prefix") + msg("env-not-found")); return; }
                    sender.sendMessage(msg("prefix") + msg("list-env-details", "name", args[2]));
                    if (envLines.isEmpty()) {
                        sender.sendMessage(msg("list-env-empty"));
                    } else {
                        for (int j = 0; j < envLines.size(); j++) {
                            sender.sendMessage("§f" + (j + 1) + ". §7" + envLines.get(j));
                        }
                    }
                }
                break;
            case "edit":
                if (args.length < 4) { sender.sendMessage(msg("error-prefix") + "/shell env edit <name> <line> <content|EOF>"); return; }
                if (args[2].equalsIgnoreCase("default")) { sender.sendMessage(msg("error-prefix") + msg("env-edit-default")); return; }
                List<String> lines = environments.get(args[2]);
                if (lines == null) { sender.sendMessage(msg("error-prefix") + msg("env-not-found")); return; }
                try {
                    int lineNum = Integer.parseInt(args[3]);
                    String content = (args.length > 4) ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : "";
                    int idx = lineNum - 1;
                    if (content.equalsIgnoreCase("EOF")) {
                        if (idx >= 0 && idx < lines.size()) lines.remove(idx);
                    } else {
                        if (idx >= 0 && idx < lines.size()) lines.set(idx, content);
                        else lines.add(content);
                    }
                    saveEnvironments();
                    sender.sendMessage(msg("prefix") + msg("env-updated", "name", args[2]));
                } catch (NumberFormatException e) { sender.sendMessage(msg("error-prefix") + msg("invalid-line-number")); }
                break;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(msg("prefix") + msg("help-header"));
        sender.sendMessage("§f" + msg("help-run"));
        sender.sendMessage("§f" + msg("help-input"));
        sender.sendMessage("§f" + msg("help-list"));
        sender.sendMessage("§f" + msg("help-stop"));
        sender.sendMessage("§f" + msg("help-env"));
    }

    private void listProcesses(CommandSender sender) {
        if (activeProcesses.isEmpty()) {
            sender.sendMessage(msg("warn-prefix") + msg("list-empty"));
            return;
        }
        sender.sendMessage(msg("prefix") + msg("list-header"));
        activeProcesses.forEach((id, mp) -> {
            long duration = (System.currentTimeMillis() - mp.startTime) / 1000;
            String status = (mp.process == null) ? msg("list-starting") : "";
            
            String stats = "";
            if (mp.process != null && mp.process.isAlive()) {
                stats = getProcessStats(mp);
            }

            sender.sendMessage(String.format("§f[%d] %s§a%s §7(%ds) %s", id, status, mp.command, duration, stats));
        });
    }

    private String getProcessStats(ManagedProcess mp) {
        ProcessHandle handle = mp.process.toHandle();
        List<ProcessHandle> tree = Stream.concat(Stream.of(handle), handle.descendants()).collect(Collectors.toList());
        
        long totalRssKb = 0;
        long totalCpuNanos = 0;

        for (ProcessHandle h : tree) {
            totalRssKb += getRssKb(h.pid());
            totalCpuNanos += h.info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(0L);
        }

        long now = System.nanoTime();
        if (mp.lastSampleTime > 0) {
            long timeDelta = now - mp.lastSampleTime;
            long cpuDelta = totalCpuNanos - mp.lastCpuNanos;
            if (timeDelta > 0) {
                mp.lastUsage = (100.0 * cpuDelta) / timeDelta;
            }
        }
        mp.lastSampleTime = now;
        mp.lastCpuNanos = totalCpuNanos;

        String rssDisplay = (totalRssKb > 1024) ? (totalRssKb / 1024 + " MB") : (totalRssKb + " kB");
        String cpuDisplay = String.format("%.1f%%", Math.min(100.0 * Runtime.getRuntime().availableProcessors(), mp.lastUsage));
        
        return msg("process-stats", "mem", rssDisplay, "cpu", cpuDisplay);
    }

    private long getRssKb(long pid) {
        if (isWindows) {
            try {
                Process p = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/NH", "/FO", "CSV").start();
                try (java.util.Scanner s = new java.util.Scanner(p.getInputStream())) {
                    if (s.hasNextLine()) {
                        String line = s.nextLine();
                        if (line.contains(",")) {
                            String[] parts = line.replace("\"", "").split(",");
                            if (parts.length >= 5) {
                                String memStr = parts[4].replaceAll("[^0-9]", "");
                                return Long.parseLong(memStr);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        } else {
            File procStatus = new File("/proc/" + pid + "/status");
            if (procStatus.exists()) {
                try {
                    List<String> lines = java.nio.file.Files.readAllLines(procStatus.toPath());
                    for (String line : lines) {
                        if (line.startsWith("VmRSS:")) {
                            return Long.parseLong(line.split(":")[1].trim().split(" ")[0]);
                        }
                    }
                } catch (Exception ignored) {}
            } else {
                try {
                    Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
                    try (java.util.Scanner s = new java.util.Scanner(p.getInputStream())) {
                        if (s.hasNextLong()) return s.nextLong();
                    }
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private void stopProcess(CommandSender sender, int id) {
        ManagedProcess mp = activeProcesses.get(id);
        if (mp != null) {
            if (mp.process != null) {
                mp.process.destroyForcibly();
            }
            sender.sendMessage(msg("warn-prefix") + msg("process-stopped", "id", id));
            activeProcesses.remove(id);
        } else {
            sender.sendMessage(msg("error-prefix") + msg("invalid-id"));
        }
    }

    private void executeAsync(String cmd, ConsoleCommandSender sender, String workDir, String envName, Integer customTimeout) {
        int id = getNextId();
        sender.sendMessage(msg("prefix") + msg("process-starting", "id", id));

        ManagedProcess mp = new ManagedProcess(null, cmd, Charset.defaultCharset());
        activeProcesses.put(id, mp);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String nativeEncoding = System.getProperty("sun.stdout.encoding");
            if (nativeEncoding == null) nativeEncoding = System.getProperty("native.encoding");
            Charset charset = (nativeEncoding != null) ? Charset.forName(nativeEncoding) : Charset.defaultCharset();

            ProcessBuilder pb = new ProcessBuilder();
            if (workDir != null) {
                File dir = new File(workDir);
                if (dir.exists() && dir.isDirectory()) pb.directory(dir);
            }

            List<String> envCommands = new ArrayList<>();
            if (envName != null && environments.containsKey(envName)) {
                for (String line : environments.get(envName)) {
                    if (line.contains("=") && !line.startsWith(" ")) {
                        String[] kv = line.split("=", 2);
                        pb.environment().put(kv[0].trim(), kv[1].trim());
                    } else {
                        envCommands.add(line);
                    }
                }
            }

            String finalCmd = cmd;
            if (!envCommands.isEmpty()) {
                String joiner = isWindows ? " & " : " && ";
                finalCmd = String.join(joiner, envCommands) + joiner + cmd;
            }

            if (isWindows) pb.command("cmd.exe", "/c", finalCmd);
            else pb.command("sh", "-c", finalCmd);

            pb.redirectErrorStream(true);

            int maxLineLength = plugin.getConfig().getInt("max-line-length", 16384);
            int bufferSize = plugin.getConfig().getInt("read-buffer-size", 8192);
            int timeout = (customTimeout != null) ? customTimeout : plugin.getConfig().getInt("default-timeout", 0);
            String idPrefix = plugin.getConfig().getString("id-prefix-color", "§8");

            try {
                Process process = pb.start();
                mp.updateProcess(process, charset);

                if (timeout > 0) {
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (process.isAlive()) {
                            sender.sendMessage(msg("error-prefix") + msg("process-timeout", "id", id));
                            process.destroyForcibly();
                            activeProcesses.remove(id);
                        }
                    }, timeout * 20L);
                }

                try (InputStream is = process.getInputStream()) {
                    byte[] buffer = new byte[bufferSize];
                    int bytesRead;
                    java.io.ByteArrayOutputStream lineBuffer = new java.io.ByteArrayOutputStream();
                    
                    int ansiState = 0; // 0: normal, 1: ESC, 2: [, 3: ( (G-set)
                    StringBuilder ansiCode = new StringBuilder();
                    boolean colorEnabled = plugin.getConfig().getBoolean("enable-color", true);

                    while ((bytesRead = is.read(buffer)) != -1) {
                        for (int i = 0; i < bytesRead; i++) {
                            int ub = buffer[i] & 0xFF;

                            // ANSI State Machine
                            if (ansiState == 0) {
                                if (ub == 27) { // ESC
                                    ansiState = 1;
                                    continue;
                                }
                            } else if (ansiState == 1) {
                                if (ub == '[') { ansiState = 2; continue; }
                                if (ub == '(') { ansiState = 3; continue; }
                                else { ansiState = 0; continue; } // Skip or reset on single-char escape
                            } else if (ansiState == 2) { // CSI sequence
                                if (ub >= 0x30 && ub <= 0x3F) { // Parameter bytes (?, ;, 0-9, etc.)
                                    ansiCode.append((char) ub);
                                    continue;
                                } else if (ub >= 0x40 && ub <= 0x7E) { // Final byte (terminator)
                                    if (ub == 'm' && colorEnabled) {
                                        String[] codes = ansiCode.toString().split(";");
                                        for (String code : codes) {
                                            try {
                                                int c = Integer.parseInt(code);
                                                String mc = ANSI_TO_MC.get(c);
                                                if (mc != null) {
                                                    byte[] mcBytes = mc.getBytes(charset);
                                                    lineBuffer.write(mcBytes);
                                                }
                                            } catch (NumberFormatException ignored) {}
                                        }
                                    }
                                    ansiCode.setLength(0);
                                    ansiState = 0;
                                    continue;
                                }
                                ansiState = 0; // Lost in sequence, reset
                                continue;
                            } else if (ansiState == 3) { // G-set sequence (swallow one char)
                                ansiState = 0;
                                continue;
                            }

                            // Regular character handling (only if ansiState == 0)
                            if (ub == 10 || ub == 13) { // LF or CR
                                if (lineBuffer.size() > 0) {
                                    sendFormattedMessage(sender, idPrefix, id, lineBuffer.toString(charset));
                                    lineBuffer.reset();
                                }
                                continue;
                            }

                            if (ub < 32 && ub != 9) { // Non-printable (excluding tab)
                                continue; // Just skip instead of killing process
                            }

                            lineBuffer.write(ub);
                            if (lineBuffer.size() > maxLineLength) {
                                sendFormattedMessage(sender, idPrefix, id, lineBuffer.toString(charset) + " §7(truncated...)");
                                lineBuffer.reset();
                            }
                        }
                        
                        // Flush remaining content if no more data is available right now
                        if (lineBuffer.size() > 0 && is.available() == 0) {
                            sendFormattedMessage(sender, idPrefix, id, lineBuffer.toString(charset));
                            lineBuffer.reset();
                        }
                    }
                }

                int exitCode = process.waitFor();
                if (activeProcesses.containsKey(id)) {
                    sender.sendMessage(msg("warn-prefix") + msg("process-exited", "id", id, "code", exitCode));
                    activeProcesses.remove(id);
                }
            } catch (Exception e) {
                if (activeProcesses.containsKey(id)) {
                    sender.sendMessage(msg("error-prefix") + msg("process-error", "id", id, "error", e.getMessage()));
                    activeProcesses.remove(id);
                }
            }
        });
    }

    private void sendFormattedMessage(ConsoleCommandSender sender, String prefixColor, int id, String message) {
        if (message.isEmpty()) return;
        sender.sendMessage(prefixColor + "[" + id + "]§r " + message);
    }

    public void cleanup() {
        if (!activeProcesses.isEmpty()) {
            plugin.getLogger().info("Stopping " + activeProcesses.size() + " active shell processes...");
            activeProcesses.forEach((id, mp) -> {
                if (mp.process != null) {
                    mp.process.destroyForcibly();
                }
            });
            activeProcesses.clear();
        }
    }
}