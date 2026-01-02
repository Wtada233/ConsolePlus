package org.linuxfirmware.consolePlus.utils;

import java.util.Map;

public class AnsiConverter {
    private static final Map<Integer, String> ANSI_TO_MC = Map.ofEntries(
        Map.entry(0, "§r"), Map.entry(1, "§l"),
        Map.entry(30, "§0"), Map.entry(31, "§c"), Map.entry(32, "§a"), Map.entry(33, "§e"),
        Map.entry(34, "§9"), Map.entry(35, "§5"), Map.entry(36, "§b"), Map.entry(37, "§7"),
        Map.entry(90, "§8"), Map.entry(91, "§c"), Map.entry(92, "§a"), Map.entry(93, "§e"),
        Map.entry(94, "§9"), Map.entry(95, "§d"), Map.entry(96, "§b"), Map.entry(97, "§f")
    );

    public static String toMinecraft(int ansiCode) {
        return ANSI_TO_MC.get(ansiCode);
    }
}
