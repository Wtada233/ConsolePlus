package org.linuxfirmware.consolePlus.vendor;

import java.util.HashMap;
import java.util.Map;

/**
 * 轻量级命令行参数解析器。
 */
public class SimpleArgParser {
    private final Map<String, String> flags = new HashMap<>();
    private int remainingIndex;

    public void parse(String[] args, int startIndex) {
        int i = startIndex;
        while (i < args.length) {
            String arg = args[i];
            if (arg.startsWith("-") && arg.length() > 1 && i + 1 < args.length) {
                flags.put(arg, args[i + 1]);
                i += 2;
            } else {
                break;
            }
        }
        remainingIndex = i;
    }

    public String getFlag(String flag, String defaultValue) {
        return flags.getOrDefault(flag, defaultValue);
    }

    public Integer getIntFlag(String flag) {
        String val = flags.get(flag);
        if (val == null) return null;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int getRemainingIndex() {
        return remainingIndex;
    }
}
