package org.linuxfirmware.consolePlus;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.linuxfirmware.consolePlus.managers.EnvironmentManager;
import org.linuxfirmware.consolePlus.managers.ProcessManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import org.apache.commons.exec.CommandLine;

public class ShellCommand implements CommandExecutor, TabCompleter {
    private static final java.util.Set<String> SHELL_OPERATORS = java.util.Set.of(
        ">", ">>", "<", "<<", "|", "||", "&&", ";", "&", "1>", "2>", "2>&1", ">&", "!"
    );
    private final ConsolePlus plugin;
    private final EnvironmentManager envManager;
    private final ProcessManager processManager;
    private final java.util.Set<String> systemCommands = new ConcurrentSkipListSet<>();
    private final boolean isWindows;
    private String selectedEnv = "default";

    public ShellCommand(ConsolePlus plugin) {
        this.plugin = plugin;
        this.envManager = new EnvironmentManager(plugin);
        this.processManager = new ProcessManager(plugin, envManager);
        this.isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
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
                        return processManager.getActiveIds().stream().map(String::valueOf).collect(Collectors.toList());
                    }
                    break;
                case "run":
                    int currentPos = args.length - 1;
                    if (args.length >= 3) {
                        String prev = args[currentPos - 1];
                        if (prev.equals("-d")) return completePath(args[currentPos], true, ".");
                        if (prev.equals("-e")) return filterStrings(new ArrayList<>(envManager.getEnvironments().keySet()), args[currentPos]);
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
                        return filterStrings(new ArrayList<>(envManager.getEnvironments().keySet()), args[2]);
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
            parent = new File(baseDir);
            prefix = "";
            pathPrefix = "";
            
            if (File.listRoots() != null) {
                for (File root : File.listRoots()) {
                    String rootPath = root.getAbsolutePath();
                    if (isWindows) {
                         suggestions.add(rootPath);
                    } else {
                         if (!suggestions.contains("/")) suggestions.add("/");
                    }
                }
            }
        } else {
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
                     parent = file.getParentFile();
                     if (parent == null) parent = isAbsolute ? new File(input) : new File(baseDir);
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
            case "list": plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> processManager.listProcesses((ConsoleCommandSender)sender)); break;
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
        if (envName != null && !envManager.exists(envName)) {
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
        processManager.executeAsync(cmdBuilder.toString(), (ConsoleCommandSender) sender, workDir, envName, customTimeout);
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(msg("error-prefix") + "Usage: /shell stop <id>");
            return;
        }
        try {
            int id = Integer.parseInt(args[1]);
            if (processManager.stopProcess(id)) {
                sender.sendMessage(msg("warn-prefix") + msg("process-stopped", "id", id));
            } else {
                sender.sendMessage(msg("error-prefix") + msg("invalid-id"));
            }
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
            try {
                processManager.sendInput(id, input);
                sender.sendMessage(msg("prefix") + msg("input-sent", "id", id));
            } catch (Exception e) {
                sender.sendMessage(msg("error-prefix") + e.getMessage());
            }
        } catch (NumberFormatException e) { sender.sendMessage(msg("error-prefix") + msg("invalid-id")); }
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
                envManager.createEnvironment(args[2]);
                sender.sendMessage(msg("prefix") + msg("env-created", "name", args[2]));
                break;
            case "select":
                if (args.length < 3) { sender.sendMessage(msg("error-prefix") + "/shell env select <name>"); return; }
                if (!envManager.exists(args[2])) {
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
                envManager.deleteEnvironment(args[2]);
                if (args[2].equals(selectedEnv)) {
                    selectedEnv = "default";
                    sender.sendMessage(msg("warn-prefix") + msg("env-active-deleted"));
                }
                sender.sendMessage(msg("prefix") + msg("env-deleted"));
                break;
            case "list":
                if (args.length < 3) {
                    sender.sendMessage(msg("prefix") + msg("list-env-header"));
                    envManager.getEnvironments().forEach((name, lines) -> {
                        String prefix = name.equals(selectedEnv) ? "§6* " : "§f- ";
                        sender.sendMessage(prefix + name + " §7(" + lines.size() + " lines)");
                    });
                    sender.sendMessage(msg("list-env-usage"));
                } else {
                    List<String> envLines = envManager.getEnvironment(args[2]);
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
                List<String> lines = envManager.getEnvironment(args[2]);
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
                    envManager.saveEnvironments();
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

    public void cleanup() {
        processManager.cleanup();
    }
}
