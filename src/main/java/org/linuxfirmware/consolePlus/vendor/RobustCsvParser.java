package org.linuxfirmware.consolePlus.vendor;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 RFC 4180 标准的 CSV 解析器。
 * 适配自经典的轻量级 CSV 实现，确保处理带引号和逗号的字段时足够稳健。
 */
public class RobustCsvParser {
    private static final char QUOTE = '"';
    private static final char COMMA = ',';

    public static List<String> parseLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null || line.isEmpty()) return result;

        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        char[] chars = line.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (inQuotes) {
                if (c == QUOTE) {
                    if (i + 1 < chars.length && chars[i + 1] == QUOTE) {
                        sb.append(QUOTE);
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == QUOTE) {
                    inQuotes = true;
                } else if (c == COMMA) {
                    result.add(sb.toString().trim());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        result.add(sb.toString().trim());
        return result;
    }
}
