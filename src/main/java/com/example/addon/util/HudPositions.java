package com.example.addon.util;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Tiny persistent store for HUD positions, saved to
 * <game-dir>/boze-addon/hud_positions.properties.
 *
 * Modules removed their X/Y SliderOptions (which used to persist via Boze's
 * config), so positions are kept here instead: read on construction with
 * {@link #getX}/{@link #getY}, written once on drag-release with {@link #save}.
 */
public final class HudPositions {

    private static final File FILE = FabricLoader.getInstance().getGameDir()
            .resolve("boze-addon/hud_positions.properties").toFile();
    private static final Properties PROPS = new Properties();
    private static boolean loaded = false;

    private HudPositions() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (FILE.exists()) {
            try (FileInputStream in = new FileInputStream(FILE)) {
                PROPS.load(in);
            } catch (Exception ignored) {}
        }
    }

    public static double getX(String key, double def) { return get(key + ".x", def); }
    public static double getY(String key, double def) { return get(key + ".y", def); }

    private static synchronized double get(String prop, double def) {
        ensureLoaded();
        try {
            String v = PROPS.getProperty(prop);
            return v == null ? def : Double.parseDouble(v);
        } catch (Exception e) {
            return def;
        }
    }

    public static synchronized void save(String key, double x, double y) {
        ensureLoaded();
        PROPS.setProperty(key + ".x", Double.toString(x));
        PROPS.setProperty(key + ".y", Double.toString(y));
        try {
            File parent = FILE.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(FILE)) {
                PROPS.store(out, "Boze HUD positions");
            }
        } catch (Exception ignored) {}
    }
}
