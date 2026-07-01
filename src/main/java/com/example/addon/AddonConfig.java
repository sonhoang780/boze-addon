package com.example.addon;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Properties;

public class AddonConfig {
    private static final File FILE = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/addon_data.properties");
    private static final Properties PROPS = new Properties();

    public static void load() {
        if (FILE.exists()) {
            try (FileReader r = new FileReader(FILE)) {
                PROPS.load(r);
            } catch (Exception e) {}
        }
    }

    public static void save() {
        try {
            FILE.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(FILE)) {
                PROPS.store(w, "Boze Addon Data");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String get(String key, String def) {
        return PROPS.getProperty(key, def);
    }

    public static void set(String key, String value) {
        PROPS.setProperty(key, value);
        save();
    }
}
