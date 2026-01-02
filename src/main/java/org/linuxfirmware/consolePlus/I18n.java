package org.linuxfirmware.consolePlus;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class I18n {
    private final JavaPlugin plugin;
    private YamlConfiguration langConfig;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public I18n(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        cache.clear();
        
        // Ensure languages directory exists
        File langDir = new File(plugin.getDataFolder(), "languages");
        if (!langDir.exists()) langDir.mkdirs();

        // Extract all supported languages if missing
        String[] supportedLangs = {"en_US", "zh_CN"};
        for (String lang : supportedLangs) {
            File langFile = new File(langDir, lang + ".yml");
            if (!langFile.exists()) {
                plugin.saveResource("languages/" + lang + ".yml", false);
            }
        }

        String langName = plugin.getConfig().getString("language", "zh_CN");
        File currentLangFile = new File(langDir, langName + ".yml");
        
        try (java.io.FileInputStream fis = new java.io.FileInputStream(currentLangFile);
             java.io.InputStreamReader reader = new java.io.InputStreamReader(fis, StandardCharsets.UTF_8)) {
            langConfig = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not load language file: " + currentLangFile.getName());
            langConfig = new YamlConfiguration();
        }
        
        // Load default values from JAR if missing in file
        InputStream defStream = plugin.getResource("languages/" + langName + ".yml");
        if (defStream != null) {
            langConfig.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defStream, StandardCharsets.UTF_8)));
        }
    }

    public String get(String key) {
        return cache.computeIfAbsent(key, k -> {
            String val = langConfig.getString(k);
            return val != null ? val.replace("&", "§") : "Missing key: " + k;
        });
    }

    public String get(String key, Map<String, Object> placeholders) {
        String msg = get(key);
        for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
            msg = msg.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return msg;
    }
}
