package org.linuxfirmware.consolePlus;

import org.bukkit.plugin.java.JavaPlugin;

public final class ConsolePlus extends JavaPlugin {

    private ShellCommand shellCommand;
    private I18n i18n;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.i18n = new I18n(this);
        if (getCommand("shell") != null) {
            this.shellCommand = new ShellCommand(this);
            getCommand("shell").setExecutor(shellCommand);
            getCommand("shell").setTabCompleter(shellCommand);
        }
    }

    public I18n getI18n() {
        return i18n;
    }

    @Override
    public void onDisable() {
        if (shellCommand != null) {
            shellCommand.cleanup();
        }
    }
}
