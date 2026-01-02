package org.linuxfirmware.consolePlus;

import org.bukkit.ChatColor;
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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.apache.commons.exec.CommandLine;

public class ShellCommand implements CommandExecutor, TabCompleter {
    private static final Map<Integer, String> ANSI_TO_MC = Map.ofEntries(
        Map.entry(0, "§r"), Map.entry(1, "§l"),
        Map.entry(30, "§0"), Map.entry(31, "§c"), Map.entry(32, "§a"), Map.entry(33, "§e"),
        Map.entry(34, "§9"), Map.entry(35, "§5"), Map.entry(36, "§b"), Map.entry(37, "§7"),
        Map.entry(90, "§8"), Map.entry(91, "§c"), Map.entry(92, "§a"), Map.entry(93, "§e"),
        Map.entry(94, "§9"), Map.entry(95, "§d"), Map.entry(96, "§b"), Map.entry(97, "§f")
    );
    private static final java.util.Set<String> SHELL_OPERATORS = java.util.Set.of(
        ">", ">>", "<", "<<", "|", "||", "&&", ";", "&", "1>", "2>", "2>&1", ">&", "!"
    );
    private final ConsolePlus plugin;
    private final Map<Integer, ManagedProcess> activeProcesses = new ConcurrentHashMap<>();
    private final Map<String, List<String>> environments = new ConcurrentHashMap<>();
    private final java.util.Set<String> systemCommands = new ConcurrentSkipListSet<>();
    private final File envFile;
    private final boolean isWindows;
    private String selectedEnv = "default";
    private final Map<Long, Long> windowsStatsCache = new HashMap<>();
    private long lastStatsUpdate = 0;

    public ShellCommand(ConsolePlus plugin) {
        this.plugin = plugin;
        this.envFile = new File(plugin.getDataFolder(), "environments.yml");
        this.isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        loadEnvironments();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::refreshSystemCommands);
    }

    private void refreshSystemCommands() {
        systemCommands.clear();
        String path = System.getenv("PATH");
        if (path == null) path = System.getenv("Path");
        if (path == null) return;

        String separator = isWindows ? ";" : ":";
        String[] dirs = path.split(separator);
        for (String dir : dirs) {
            File d = new File(dir);
            if (d.exists() && d.isDirectory()) {
                File[] files = d.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && (isWindows || f.canExecute())) {
                            String name = f.getName();
                            String lower = name.toLowerCase();
                            if (isWindows) {
                                if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
                                    systemCommands.add(name.substring(0, name.lastIndexOf('.')));
                                }
                            } else {
                                systemCommands.add(name);
                            }
                        }
                    }
                }
            }
        }
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
        if (!(sender instanceof ConsoleCommandSender)) {
            return Collections.emptyList();
        }

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
                    int currentPos = args.length - 1;
                    if (args.length >= 3) {
                        String prev = args[currentPos - 1];
                        if (prev.equals("-d")) return completePath(args[currentPos], true, ".");
                        if (prev.equals("-e")) return filterStrings(new ArrayList<>(environments.keySet()), args[currentPos]);
                    }

                    int cmdPos = 1;
                    while (cmdPos < currentPos) {
                        if (args[cmdPos].equals("-d") || args[cmdPos].equals("-e") || args[cmdPos].equals("-t")) {
                            cmdPos += 2;
                        } else {
                            break;
                        }
                    }

                    if (currentPos <= cmdPos) {
                        String input = args[currentPos].toLowerCase();
                        List<String> results = new ArrayList<>();
                        if (!Arrays.asList(args).contains("-d")) results.add("-d");
                        if (!Arrays.asList(args).contains("-e")) results.add("-e");
                        if (!Arrays.asList(args).contains("-t")) results.add("-t");
                        systemCommands.stream().filter(s -> s.toLowerCase().startsWith(input)).limit(50).forEach(results::add);
                        return filterStrings(results, input);
                    } else {
                        String workDirStr = ".";
                        for (int k = 0; k < args.length - 1; k++) {
                            if (args[k].equals("-d") && k + 1 < args.length) {
                                workDirStr = args[k+1];
                                break;
                            }
                        }
                        return completePath(args[currentPos], false, workDirStr);
                    }
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

    private List<String> completePath(String input, boolean onlyDirs, String baseDir) {
        List<String> suggestions = new ArrayList<>();
        File parent;
        String prefix;
        String pathPrefix;

        if (input.isEmpty()) {
            // Handle empty input: list baseDir content + roots
            parent = new File(baseDir);
            prefix = "";
            pathPrefix = "";
            
            // Add root suggestions
            if (File.listRoots() != null) {
                for (File root : File.listRoots()) {
                    String rootPath = root.getAbsolutePath();
                    // On Windows, listRoots returns "C:\", on Linux "/"
                    if (isWindows) {
                         suggestions.add(rootPath);
                    } else {
                         // On Linux, listRoots returns "/", just add it if not already handled
                         if (!suggestions.contains("/")) suggestions.add("/");
                    }
                }
            }
        } else {
            // Existing logic for non-empty input
            File file;
            boolean isAbsolute = input.startsWith("/") || input.startsWith("\\") || (input.length() > 1 && input.charAt(1) == ':');
            
            if (isAbsolute) {
                file = new File(input);
            } else {
                file = new File(baseDir, input);
            }

            if (input.endsWith("/") || input.endsWith("\\") || (file.exists() && file.isDirectory())) {
                parent = file;
                prefix = "";
                if (!input.endsWith("/") && !input.endsWith("\\")) {
                    // If it's a directory but doesn't end with separator, we treat it as complete match for itself,
                    // BUT if the user wants to go inside, they usually type /.
                    // Here we assume if they typed a valid dir name without slash, they might want to complete inside it?
                    // Actually, standard shell behavior: if 'foo' is dir, tab completes to 'foo/', then next tab lists inside.
                    // But here we are generating the list.
                    // Let's stick to: if it ends with separator, list children.
                    // If not, we fall back to parent logic to match this name.
                     parent = file.getParentFile();
                     if (parent == null) parent = isAbsolute ? new File(input) : new File(baseDir); // Fallback
                     prefix = file.getName().toLowerCase();
                }
            } else {
                parent = file.getParentFile();
                if (parent == null) {
                    if (isAbsolute) return Collections.emptyList();
                    parent = new File(baseDir);
                }
                prefix = file.getName().toLowerCase();
            }
            
            int lastSlash = Math.max(input.lastIndexOf('/'), input.lastIndexOf('\\'));
            pathPrefix = (lastSlash >= 0) ? input.substring(0, lastSlash + 1) : "";
        }

        if (parent != null && parent.exists() && parent.isDirectory()) {
            File[] files = parent.listFiles(onlyDirs ? File::isDirectory : null);
            if (files != null) {
                for (File f : files) {
                    if (f.getName().toLowerCase().startsWith(prefix)) {
                        String suffix = f.isDirectory() ? File.separator : "";
                        suggestions.add(pathPrefix + f.getName() + suffix);
                    }
                }
            }
        }
        
        Collections.sort(suggestions);
        return suggestions;
    }

    private void loadEnvironments() {
        environments.put("default", new CopyOnWriteArrayList<>());
        if (!envFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(envFile);
        for (String key : config.getKeys(false)) {
            if (!key.equalsIgnoreCase("default")) {
                environments.put(key, new CopyOnWriteArrayList<>(config.getStringList(key)));
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
        BufferedWriter logWriter;
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
            case "run": handleRun(sender, args); break;
            case "list": plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> listProcesses(sender)); break;
            case "stop": handleStop(sender, args); break;
            case "input": handleInput(sender, args); break;
            case "env": handleEnv(sender, args); break;
            case "help":
            default: sendHelp(sender); break;
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
            if (args[i].equals("-d") && i + 1 < args.length) { workDir = args[i + 1]; i += 2; }
            else if (args[i].equals("-e") && i + 1 < args.length) { envName = args[i + 1]; i += 2; }
            else if (args[i].equals("-t") && i + 1 < args.length) {
                try {
                    customTimeout = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(msg("error-prefix") + msg("invalid-timeout"));
                    return;
                }
                i += 2;
            } else break;
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
        StringBuilder cmdBuilder = new StringBuilder();
        for (int j = i; j < args.length; j++) {
            String arg = args[j];
            if (SHELL_OPERATORS.contains(arg)) cmdBuilder.append(arg);
            else {
                CommandLine cl = new CommandLine("fake");
                cl.addArgument(arg, true);
                String[] strings = cl.toStrings();
                if (strings.length > 1) cmdBuilder.append(strings[1]);
                else if (arg.isEmpty()) cmdBuilder.append("\"\"");
            }
            if (j < args.length - 1) cmdBuilder.append(" ");
        }
        executeAsync(cmdBuilder.toString(), (ConsoleCommandSender) sender, workDir, envName, customTimeout);
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("error-prefix") + "Usage: /shell stop <id>");
            return;
        }
        try {
            int id = Integer.parseInt(args[1]);
            stopProcess(sender, id);
        } catch (NumberFormatException e) { sender.sendMessage(msg("error-prefix") + msg("invalid-id")); }
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
                mp.writer.write("\n");
                mp.writer.flush();
                sender.sendMessage(msg("prefix") + msg("input-sent", "id", id));
            } else sender.sendMessage(msg("error-prefix") + msg("process-not-found"));
        } catch (Exception e) { sender.sendMessage(msg("error-prefix") + "Error: " + e.getMessage()); }
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
                environments.putIfAbsent(args[2], new CopyOnWriteArrayList<>());
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
                    if (envLines.isEmpty()) sender.sendMessage(msg("list-env-empty"));
                    else {
                        for (int j = 0; j < envLines.size(); j++) sender.sendMessage("§f" + (j + 1) + ". §7" + envLines.get(j));
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
            String stats = (mp.process != null && mp.process.isAlive()) ? getProcessStats(mp) : "";
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
            if (timeDelta > 0) mp.lastUsage = (100.0 * cpuDelta) / timeDelta;
        }
        mp.lastSampleTime = now;
        mp.lastCpuNanos = totalCpuNanos;
        String rssDisplay = (totalRssKb > 1024) ? (totalRssKb / 1024 + " MB") : (totalRssKb + " kB");
        String cpuDisplay = String.format("%.1f%%", Math.min(100.0 * Runtime.getRuntime().availableProcessors(), mp.lastUsage));
        return msg("process-stats", "mem", rssDisplay, "cpu", cpuDisplay);
    }

    private Charset getNativeCharset() {
        String enc = System.getProperty("sun.stdout.encoding") != null ? System.getProperty("sun.stdout.encoding") : System.getProperty("native.encoding");
        return (enc != null) ? Charset.forName(enc) : Charset.defaultCharset();
    }

    private long getRssKb(long pid) {
        if (isWindows) {
            synchronized (windowsStatsCache) {
                long now = System.currentTimeMillis();
                if (now - lastStatsUpdate > 5000) {
                    windowsStatsCache.clear();
                    try {
                        Process p = new ProcessBuilder("tasklist", "/NH", "/FO", "CSV").start();
                        try (java.util.Scanner s = new java.util.Scanner(p.getInputStream(), getNativeCharset())) {
                            while (s.hasNextLine()) {
                                String line = s.nextLine();
                                if (line.contains(",")) {
                                    String[] parts = line.replace("\"", "").split(",");
                                    if (parts.length >= 5) {
                                        try {
                                            long pId = Long.parseLong(parts[1]);
                                            long mem = Long.parseLong(parts[4].replaceAll("[^0-9]", ""));
                                            windowsStatsCache.put(pId, mem);
                                        } catch (NumberFormatException ignored) {}
                                    }
                                }
                            }
                        }
                        lastStatsUpdate = now;
                    } catch (Exception ignored) {}
                }
                return windowsStatsCache.getOrDefault(pid, 0L);
            }
        } else {
            File procStatus = new File("/proc/" + pid + "/status");
            if (procStatus.exists()) {
                try {
                    List<String> lines = java.nio.file.Files.readAllLines(procStatus.toPath());
                    for (String line : lines) {
                        if (line.startsWith("VmRSS:")) return Long.parseLong(line.split(":")[1].trim().split(" ")[0]);
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
                mp.process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                mp.process.destroyForcibly();
            }
            sender.sendMessage(msg("warn-prefix") + msg("process-stopped", "id", id));
            activeProcesses.remove(id);
        } else sender.sendMessage(msg("error-prefix") + msg("invalid-id"));
    }

    private void executeAsync(String cmd, ConsoleCommandSender sender, String workDir, String envName, Integer customTimeout) {
        int id = getNextId();
        
        // Validate working directory before reserving ID fully
        if (workDir != null) {
            File dir = new File(workDir);
            if (!dir.exists() || !dir.isDirectory()) {
                sender.sendMessage(msg("error-prefix") + msg("process-error", "id", id, "error", msg("invalid-workdir", "dir", workDir)));
                return;
            }
        }

        sender.sendMessage(msg("prefix") + msg("process-starting", "id", id));
        ManagedProcess mp = new ManagedProcess(null, cmd, Charset.defaultCharset());
        activeProcesses.put(id, mp);
        if (plugin.getConfig().getBoolean("enable-process-logging", true)) {
            try {
                File logDir = new File(plugin.getDataFolder(), plugin.getConfig().getString("process-log-dir", "logs"));
                if (!logDir.exists()) logDir.mkdirs();
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                File logFile = new File(logDir, "process-" + id + "-" + timestamp + ".log");
                mp.logWriter = new BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(logFile), StandardCharsets.UTF_8));
                mp.logWriter.write("Command: " + cmd + "\nStart Time: " + new java.util.Date() + "\n------------------------------------------\n");
                mp.logWriter.flush();
            } catch (IOException e) { plugin.getLogger().warning("Could not create log file for process " + id + ": " + e.getMessage()); }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Charset charset = getNativeCharset();
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
                    } else if (!line.trim().isEmpty()) {
                        envCommands.add(line.trim());
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

                // New decoding logic using CharsetDecoder and ByteBuffer
                CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
                ByteBuffer dataBuffer = ByteBuffer.allocate(maxLineLength + bufferSize);

                try (InputStream is = process.getInputStream()) {
                    byte[] rawBuffer = new byte[bufferSize];
                    int bytesRead;
                    int ansiState = 0;
                    StringBuilder ansiCode = new StringBuilder();
                    boolean colorEnabled = plugin.getConfig().getBoolean("enable-color", true);
                    
                    while ((bytesRead = is.read(rawBuffer)) != -1) {
                        for (int k = 0; k < bytesRead; k++) {
                            int ub = rawBuffer[k] & 0xFF;
                            if (ansiState == 0) {
                                if (ub == 27) { ansiState = 1; continue; }
                            } else if (ansiState == 1) {
                                if (ub == '[') { ansiState = 2; continue; }
                                if (ub == '(') { ansiState = 3; continue; }
                                else { ansiState = 0; continue; }
                            } else if (ansiState == 2) {
                                if (ub >= 0x30 && ub <= 0x3F) { ansiCode.append((char) ub); continue; }
                                else if (ub >= 0x40 && ub <= 0x7E) {
                                    if (ub == 'm' && colorEnabled) {
                                        for (String code : ansiCode.toString().split(";")) {
                                            try {
                                                String mc = ANSI_TO_MC.get(Integer.parseInt(code));
                                                if (mc != null) dataBuffer.put(mc.getBytes(charset));
                                            } catch (NumberFormatException ignored) {}
                                        }
                                    }
                                    ansiCode.setLength(0); ansiState = 0; continue;
                                }
                                ansiState = 0; continue;
                            } else if (ansiState == 3) { ansiState = 0; continue; }
                            
                            // Handle Newlines
                            if (ub == 10 || ub == 13) {
                                flushBuffer(dataBuffer, decoder, sender, idPrefix, id);
                                continue;
                            }
                            
                            if (ub < 32 && ub != 9) continue; // Skip other control chars
                            
                            dataBuffer.put((byte) ub);
                            
                            if (dataBuffer.position() > maxLineLength) {
                                flushBuffer(dataBuffer, decoder, sender, idPrefix, id);
                                sender.sendMessage(idPrefix + "[" + id + "]§r §7(line truncated...)");
                            }
                        }
                        
                        // Flush if stream paused (e.g. prompts)
                        if (is.available() == 0 && dataBuffer.position() > 0) {
                            flushBuffer(dataBuffer, decoder, sender, idPrefix, id);
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
            } finally {
                if (mp.writer != null) try { mp.writer.close(); } catch (IOException ignored) {}
                if (mp.logWriter != null) {
                    try {
                        mp.logWriter.write("------------------------------------------\nEnd Time: " + new java.util.Date() + "\n");
                        mp.logWriter.close();
                    } catch (IOException ignored) {}
                }
            }
        });
    }

    private void flushBuffer(ByteBuffer buffer, CharsetDecoder decoder, ConsoleCommandSender sender, String idPrefix, int id) {
        buffer.flip();
        if (buffer.hasRemaining()) {
            CharBuffer chars = CharBuffer.allocate(buffer.remaining());
            decoder.decode(buffer, chars, false);
            chars.flip();
            String line = chars.toString();
            if (!line.isEmpty()) {
                sendFormattedMessage(sender, idPrefix, id, line);
            }
            buffer.compact();
        } else {
            buffer.clear();
        }
    }

    private void sendFormattedMessage(ConsoleCommandSender sender, String prefixColor, int id, String message) {
        if (message.isEmpty()) return;
        sender.sendMessage(prefixColor + "[" + id + "]§r " + message);
        ManagedProcess mp = activeProcesses.get(id);
        if (mp != null && mp.logWriter != null) {
            try {
                String plain = ChatColor.stripColor(message);
                mp.logWriter.write("[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "] " + plain + "\n");
                mp.logWriter.flush();
            } catch (IOException ignored) {}
        }
    }

    public void cleanup() {
        if (!activeProcesses.isEmpty()) {
            plugin.getLogger().info("Stopping " + activeProcesses.size() + " active shell processes...");
            activeProcesses.forEach((id, mp) -> {
                if (mp.process != null) mp.process.destroyForcibly();
                if (mp.writer != null) try { mp.writer.close(); } catch (IOException ignored) {}
                if (mp.logWriter != null) try { mp.logWriter.close(); } catch (IOException ignored) {}
            });
            activeProcesses.clear();
        }
    }
}