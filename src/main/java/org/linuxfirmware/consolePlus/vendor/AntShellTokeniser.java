package org.linuxfirmware.consolePlus.vendor;

import java.util.ArrayList;
import java.util.List;

/**
 * 适配自 Apache Ant 的 Commandline.translateCommandline 逻辑。
 * 手动解析以避免 StringTokenizer 在某些环境下对引号处理的歧义。
 */
public class AntShellTokeniser {
    
    public static String[] tokenise(String toProcess) {
        if (toProcess == null || toProcess.isEmpty()) return new String[0];

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < toProcess.length(); i++) {
            char c = toProcess.charAt(i);
            if (c == '\'') {
                if (inDoubleQuote) {
                    current.append(c);
                } else {
                    inQuote = !inQuote;
                }
            } else if (c == '"') {
                if (inQuote) {
                    current.append(c);
                } else {
                    inDoubleQuote = !inDoubleQuote;
                }
            } else if (c == ' ' && !inQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result.toArray(new String[0]);
    }

    public static List<String> splitOperators(String[] tokens) {
        List<String> result = new ArrayList<>();
        String ops = "><|;&!";
        for (String token : tokens) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (ops.indexOf(c) != -1) {
                    if (sb.length() > 0) {
                        result.add(sb.toString());
                        sb.setLength(0);
                    }
                    if (i + 1 < token.length() && token.charAt(i+1) == c && "><|&".indexOf(c) != -1) {
                        result.add("" + c + c);
                        i++;
                    } else {
                        result.add("" + c);
                    }
                } else {
                    sb.append(c);
                }
            }
            if (sb.length() > 0) result.add(sb.toString());
        }
        return result;
    }
}