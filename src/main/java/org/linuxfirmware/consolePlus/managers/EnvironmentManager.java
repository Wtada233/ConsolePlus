package org.linuxfirmware.consolePlus.managers;

import org.bukkit.configuration.file.YamlConfiguration;
import org.linuxfirmware.consolePlus.ConsolePlus;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EnvironmentManager {
    private final ConsolePlus plugin;
    private final File envFile;
    private final Map<String, List<String>> environments = new ConcurrentHashMap<>();

    public EnvironmentManager(ConsolePlus plugin) {
        this.plugin = plugin;
        this.envFile = new File(plugin.getDataFolder(), "environments.yml");
        loadEnvironments();
    }

    public void loadEnvironments() {
        environments.clear();
        environments.put("default", new CopyOnWriteArrayList<>());
        if (!envFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(envFile);
        for (String key : config.getKeys(false)) {
            if (!key.equalsIgnoreCase("default")) {
                environments.put(key, new CopyOnWriteArrayList<>(config.getStringList(key)));
            }
        }
    }

    public void saveEnvironments() {
        YamlConfiguration config = new YamlConfiguration();
        environments.forEach((name, lines) -> {
            if (!name.equalsIgnoreCase("default")) {
                config.set(name, lines);
            }
        });
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            config.save(envFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save environments: " + e.getMessage());
        }
    }

    public Map<String, List<String>> getEnvironments() {
        return environments;
    }

    public List<String> getEnvironment(String name) {
        return environments.get(name);
    }
    
    public boolean exists(String name) {
        return environments.containsKey(name);
    }

    public void createEnvironment(String name) {
        environments.putIfAbsent(name, new CopyOnWriteArrayList<>());
        saveEnvironments();
    }

    public void deleteEnvironment(String name) {
        environments.remove(name);
        saveEnvironments();
    }
}
