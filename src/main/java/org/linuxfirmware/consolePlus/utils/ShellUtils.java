package org.linuxfirmware.consolePlus.utils;

import org.apache.commons.exec.CommandLine;
import java.util.Set;

public class ShellUtils {
    public static final Set<String> SHELL_OPERATORS = Set.of(
        ">", ">>", "<", "<<", "|", "||", "&&", ";", "&", "1>", "2>", "2>&1", ">&", "!"
    );

    public static String buildCommand(String[] args, int startIndex) {
        StringBuilder cmdBuilder = new StringBuilder();
        for (int j = startIndex; j < args.length; j++) {
            String arg = args[j];
            if (SHELL_OPERATORS.contains(arg)) {
                cmdBuilder.append(arg);
            } else {
                CommandLine cl = new CommandLine("fake");
                cl.addArgument(arg, true);
                String[] strings = cl.toStrings();
                if (strings.length > 1) {
                    cmdBuilder.append(strings[1]);
                } else if (arg.isEmpty()) {
                    cmdBuilder.append("\"\"");
                }
            }
            if (j < args.length - 1) {
                cmdBuilder.append(" ");
            }
        }
        return cmdBuilder.toString();
    }
}
