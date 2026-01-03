package org.linuxfirmware.consolePlus.utils;

import java.util.List;
import java.util.Set;
import org.linuxfirmware.consolePlus.vendor.AntShellTokeniser;
import org.linuxfirmware.consolePlus.vendor.RobustQuoter;

public class ShellUtils {
    /**
     * 构建最终执行的命令行字符串。
     */
    public static String buildCommand(String[] args, int startIndex) {
        StringBuilder cmdBuilder = new StringBuilder();
        Set<String> ops = Set.of(">>", "<<", "2>&1", "1>", "2>", ">&", "||", "&&", ">", "<", "|", ";", "&", "!");

        for (int j = startIndex; j < args.length; j++) {
            String arg = args[j];
            if (arg.isEmpty()) {
                cmdBuilder.append("\"\"");
            } else {
                // 使用单文件分词器处理原始参数
                String[] rawTokens = AntShellTokeniser.tokenise(arg);
                List<String> tokens = AntShellTokeniser.splitOperators(rawTokens);
                
                for (String token : tokens) {
                    if (ops.contains(token)) {
                        cmdBuilder.append(token);
                    } else {
                        // 使用 RobustQuoter 进行跨平台参数转义
                        cmdBuilder.append(RobustQuoter.quote(token));
                    }
                }
            }
            if (j < args.length - 1) {
                cmdBuilder.append(" ");
            }
        }
        return cmdBuilder.toString();
    }
}
