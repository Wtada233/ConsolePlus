package org.linuxfirmware.consolePlus.managers;

import org.bukkit.command.ConsoleCommandSender;
import org.linuxfirmware.consolePlus.ConsolePlus;
import org.linuxfirmware.consolePlus.utils.AnsiConverter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProcessManager {
    private final ConsolePlus plugin;
    private final EnvironmentManager envManager;
    private final Map<Integer, ManagedProcess> activeProcesses = new ConcurrentHashMap<>();
    private final boolean isWindows;
    private final Map<Long, Long> windowsStatsCache = new HashMap<>();
    private long lastStatsUpdate = 0;

    public ProcessManager(ConsolePlus plugin, EnvironmentManager envManager) {
        this.plugin = plugin;
        this.envManager = envManager;
        this.isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
    }

    private synchronized int reserveNextId(ManagedProcess mp) {
        int id = 1;
        while (activeProcesses.containsKey(id)) {
            id++;
        }
        activeProcesses.put(id, mp);
        return id;
    }

    private String msg(String key) {
        return plugin.getI18n().get(key);
    }
    
    private String msg(String key, Map<String, Object> map) {
        return plugin.getI18n().get(key, map);
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

    private String msg(String key, String p1, Object v1, String p2, Object v2, String p3, Object v3) {
        Map<String, Object> map = new HashMap<>();
        map.put(p1, v1);
        map.put(p2, v2);
        map.put(p3, v3);
        return plugin.getI18n().get(key, map);
    }

    public void executeAsync(String cmd, ConsoleCommandSender sender, String workDir, String envName, Integer customTimeout) {
        if (workDir != null) {
            File dir = new File(workDir);
            if (!dir.exists() || !dir.isDirectory()) {
                sender.sendMessage(msg("error-prefix") + msg("process-error", "id", -1, "error", msg("invalid-workdir", "dir", workDir)));
                return;
            }
        }

        ManagedProcess mp = new ManagedProcess(null, cmd, Charset.defaultCharset());
        int id = reserveNextId(mp);

        sender.sendMessage(msg("prefix") + msg("process-starting", "id", id));
        
        if (plugin.getConfig().getBoolean("enable-process-logging", true)) {
            try {
                File logDir = new File(plugin.getDataFolder(), plugin.getConfig().getString("process-log-dir", "logs"));
                if (!logDir.exists()) logDir.mkdirs();
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                File logFile = new File(logDir, "process-" + id + "-" + timestamp + ".log");
                mp.logWriter = new BufferedWriter(new OutputStreamWriter(new java.io.FileOutputStream(logFile), StandardCharsets.UTF_8));
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
            List<String> envLines = envManager.getEnvironment(envName);
            if (envLines != null) {
                for (String line : envLines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;

                    int eqIdx = trimmed.indexOf('=');
                    if (eqIdx > 0) {
                        String key = trimmed.substring(0, eqIdx).trim();
                        String value = trimmed.substring(eqIdx + 1).trim();
                        // 如果键合法（不含空格）且不是 export/set 命令，则直接注入环境变量
                        if (!key.contains(" ") && !key.equalsIgnoreCase("export") && !key.equalsIgnoreCase("set")) {
                            pb.environment().put(key, value);
                            continue;
                        }
                    }
                    // 否则作为预执行命令处理
                    envCommands.add(trimmed);
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
                if (mp.cancelled) return;
                Process process = pb.start();
                if (mp.cancelled) {
                    process.destroyForcibly();
                    return;
                }
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
                            
                            // 忽略回车符，仅在换行符时刷新缓冲区
                            if (ub == 13) continue;
                            if (ub == 10) {
                                flushBuffer(dataBuffer, decoder, sender, idPrefix, id, false);
                                continue;
                            }

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
                                                String mc = AnsiConverter.toMinecraft(Integer.parseInt(code));
                                                if (mc != null) dataBuffer.put(mc.getBytes(charset));
                                            } catch (NumberFormatException ignored) {}
                                        }
                                    }
                                    ansiCode.setLength(0); ansiState = 0; continue;
                                }
                                ansiState = 0; continue;
                            } else if (ansiState == 3) { ansiState = 0; continue; }
                            
                            // 过滤不可见控制字符/二进制输出
                            if (ub < 32 && ub != 9) continue;
                            
                            dataBuffer.put((byte) ub);
                            
                            if (dataBuffer.position() > maxLineLength) {
                                flushBuffer(dataBuffer, decoder, sender, idPrefix, id, false);
                                sender.sendMessage(idPrefix + "[" + id + "]§r §7(line truncated...)");
                            }
                        }
                        
                        if (is.available() == 0 && dataBuffer.position() > 0) {
                            flushBuffer(dataBuffer, decoder, sender, idPrefix, id, false);
                        }
                    }

                    if (dataBuffer.position() > 0) {
                        flushBuffer(dataBuffer, decoder, sender, idPrefix, id, true);
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("Error reading from process output: " + e.getMessage());
                }
                try {
                    int exitCode = process.waitFor();
                    if (activeProcesses.containsKey(id)) {
                        sender.sendMessage(msg("warn-prefix") + msg("process-exited", "id", id, "code", exitCode));
                        activeProcesses.remove(id);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (activeProcesses.containsKey(id)) {
                        sender.sendMessage(msg("error-prefix") + msg("process-error", "id", id, "error", "Process interrupted"));
                        activeProcesses.remove(id);
                    }
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

    private void flushBuffer(ByteBuffer buffer, CharsetDecoder decoder, ConsoleCommandSender sender, String idPrefix, int id, boolean endOfInput) {
        buffer.flip();
        CharBuffer chars = CharBuffer.allocate(buffer.remaining() + (endOfInput ? 10 : 0));
        CoderResult result = decoder.decode(buffer, chars, endOfInput);
        
        if (endOfInput && result.isUnderflow()) {
            decoder.flush(chars);
        }
        
        chars.flip();
        String line = chars.toString();
        sendFormattedMessage(sender, idPrefix, id, line);
        
        if (buffer.hasRemaining()) {
            buffer.compact();
        } else {
            buffer.clear();
        }
    }

    private void sendFormattedMessage(ConsoleCommandSender sender, String prefixColor, int id, String message) {
        // 允许发送空行，确保 echo 等命令的空结果可见
        sender.sendMessage(prefixColor + "[" + id + "]§r " + message);
        ManagedProcess mp = activeProcesses.get(id);
        if (mp != null && mp.logWriter != null) {
            try {
                String plain = message.replaceAll("(?i)§[0-9a-fk-or]", "");
                mp.logWriter.write("[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "] " + plain + "\n");
                mp.logWriter.flush();
            } catch (IOException ignored) {}
        }
    }

    public boolean stopProcess(int id) {
        ManagedProcess mp = activeProcesses.remove(id);
        if (mp != null) {
            mp.cancelled = true;
            if (mp.process != null) {
                mp.process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                mp.process.destroyForcibly();
            }
            return true;
        }
        return false;
    }
    
    public void sendInput(int id, String input) throws IOException {
        ManagedProcess mp = activeProcesses.get(id);
        if (mp != null && mp.writer != null) {
            mp.writer.write(input);
            mp.writer.write("\n");
            mp.writer.flush();
        } else {
            throw new IOException("Process not found or not interactable.");
        }
    }

    public void listProcesses(ConsoleCommandSender sender) {
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

    public Set<Integer> getActiveIds() {
        return activeProcesses.keySet();
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

    private Charset getNativeCharset() {
        String enc = System.getProperty("sun.stdout.encoding") != null ? System.getProperty("sun.stdout.encoding") : System.getProperty("native.encoding");
        return (enc != null) ? Charset.forName(enc) : Charset.defaultCharset();
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
    
    private long getRssKb(long pid) {
        if (isWindows) {
            long now = System.currentTimeMillis();
            if (now - lastStatsUpdate > 5000) {
                // Trigger async update if not already updating
                synchronized (windowsStatsCache) {
                    if (now - lastStatsUpdate > 5000) {
                        lastStatsUpdate = now; // Prevent multiple triggers
                        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                            Map<Long, Long> newStats = new HashMap<>();
                            try {
                                Process p = new ProcessBuilder("tasklist", "/NH", "/FO", "CSV").start();
                                try (java.util.Scanner s = new java.util.Scanner(p.getInputStream(), getNativeCharset())) {
                                    while (s.hasNextLine()) {
                                        String line = s.nextLine();
                                        List<String> parts = org.linuxfirmware.consolePlus.vendor.RobustCsvParser.parseLine(line);
                                        if (parts.size() >= 5) {
                                            try {
                                                long pId = Long.parseLong(parts.get(1));
                                                // 稳健提取内存数值，忽略非数字字符
                                                long mem = Long.parseLong(parts.get(4).replaceAll("[^0-9]", ""));
                                                newStats.put(pId, mem);
                                            } catch (NumberFormatException ignored) {}
                                        }
                                    }
                                }
                                synchronized (windowsStatsCache) {
                                    windowsStatsCache.clear();
                                    windowsStatsCache.putAll(newStats);
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                }
            }
            synchronized (windowsStatsCache) {
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

    private static class ManagedProcess {
        Process process;
        final String command;
        final long startTime;
        BufferedWriter writer;
        BufferedWriter logWriter;
        long lastSampleTime = 0;
        long lastCpuNanos = 0;
        double lastUsage = 0.0;
        volatile boolean cancelled = false;

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
}
