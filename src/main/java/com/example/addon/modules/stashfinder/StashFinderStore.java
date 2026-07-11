package com.example.addon.modules.stashfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists which chunks (by long chunk-pos key) have already been scanned/reported so a
 * stash is never re-announced on a later visit, even across game restarts. Mirrors
 * ChestScanStore's per-world file pattern.
 */
public class StashFinderStore {

    private final Set<Long> evaluated = new HashSet<>();
    private File file;

    public static String currentWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        String base;
        if (mc.getCurrentServer() != null) {
            base = "server_" + mc.getCurrentServer().ip;
        } else if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            base = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            base = "unknown";
        }
        String dimension = mc.level != null ? mc.level.dimension().identifier().toString() : "no_dimension";
        return sanitize(base) + "__" + sanitize(dimension);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    // Rewriting the whole JSON per evaluated chunk froze the render thread at high chunk
    // rates (155KB file x 33 chunks/s on 6b6t nether travel). markEvaluated now only
    // flags dirty; StashFinder ticks flushIfStale() (10s debounce) and flush()es on
    // disable/world-switch. Crash loses at most ~10s of dedupe -- those chunks re-scan
    // next visit, and the webhook rate limiter absorbs any duplicate announce.
    private boolean dirty = false;
    private long lastSaveMs = 0;
    private static final long SAVE_INTERVAL_MS = 10_000;

    public void loadForWorld() {
        flush(); // persist the old world's pending marks before switching files
        evaluated.clear();
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/stashfinder");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, currentWorldKey() + ".json");
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<Set<Long>>() {}.getType();
            Set<Long> loaded = gson.fromJson(reader, type);
            if (loaded != null) evaluated.addAll(loaded);
        } catch (Exception ignored) {}
    }

    private void save() {
        if (file == null) return;
        try (FileWriter writer = new FileWriter(file)) {
            new GsonBuilder().create().toJson(evaluated, writer);
        } catch (Exception ignored) {}
    }

    public boolean isEvaluated(long chunkKey) {
        return evaluated.contains(chunkKey);
    }

    public void markEvaluated(long chunkKey) {
        if (evaluated.add(chunkKey)) dirty = true;
    }

    public void flushIfStale() {
        if (dirty && System.currentTimeMillis() - lastSaveMs >= SAVE_INTERVAL_MS) flush();
    }

    public void flush() {
        if (!dirty) return;
        save();
        dirty = false;
        lastSaveMs = System.currentTimeMillis();
    }

    /** Wipe the current world's dedupe file so every chunk is eligible for re-scan. */
    public void clearAll() {
        evaluated.clear();
        dirty = true;
        flush();
    }
}
