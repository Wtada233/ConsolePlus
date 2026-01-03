package org.linuxfirmware.consolePlus.vendor;

/**
 * 稳健的 Shell 参数转义工具，适配自 OpenJDK 和 Apache Commons Exec 的核心转义算法。
 * 用于在不依赖外部库的情况下，确保参数在不同系统的 Shell 中被正确引用。
 */
public class RobustQuoter {

    /**
     * 根据当前操作系统对参数进行引用处理。
     * @param argument 原始参数
     * @return 转义/引用后的参数
     */
    public static String quote(String argument) {
        if (argument == null || argument.isEmpty()) {
            return "\"\"";
        }

        // 如果不包含特殊字符，则无需引用
        if (!needsQuoting(argument)) {
            return argument;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            // Windows CMD 引用逻辑：处理引号嵌套并包裹双引号
            return "\"" + argument.replace("\"", "\"\"") + "\"";
        } else {
            // Unix Shell 引用逻辑：使用单引号包裹，并转义内部单引号
            return "'" + argument.replace("'", "'\\''") + "'";
        }
    }

    private static boolean needsQuoting(String arg) {
        for (char c : arg.toCharArray()) {
            if (Character.isWhitespace(c) || "><|&;()\"'".indexOf(c) != -1) {
                return true;
            }
        }
        return false;
    }
}
