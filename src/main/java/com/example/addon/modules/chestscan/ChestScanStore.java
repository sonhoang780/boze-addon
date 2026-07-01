package com.example.addon.modules.chestscan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChestScanStore {

    public enum ChestStatus { EMPTY, PARTIAL, FULL }

    private static class Entry {
        int x, y, z;
        String status;
    }

    private final Map<BlockPos, ChestStatus> records = new HashMap<>();
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

    public void loadForWorld() {
        records.clear();
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/chestscan");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, currentWorldKey() + ".json");
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<List<Entry>>() {}.getType();
            List<Entry> entries = gson.fromJson(reader, type);
            if (entries != null) {
                for (Entry e : entries) {
                    records.put(new BlockPos(e.x, e.y, e.z), ChestStatus.valueOf(e.status));
                }
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        if (file == null) return;
        try (FileWriter writer = new FileWriter(file)) {
            List<Entry> entries = new ArrayList<>();
            for (Map.Entry<BlockPos, ChestStatus> e : records.entrySet()) {
                Entry entry = new Entry();
                entry.x = e.getKey().getX();
                entry.y = e.getKey().getY();
                entry.z = e.getKey().getZ();
                entry.status = e.getValue().name();
                entries.add(entry);
            }
            new GsonBuilder().create().toJson(entries, writer);
        } catch (Exception ignored) {}
    }

    public void put(BlockPos pos, ChestStatus status) {
        records.put(pos.immutable(), status);
        save();
    }

    public ChestStatus get(BlockPos pos) {
        return records.get(pos);
    }

    public void remove(BlockPos pos) {
        if (records.remove(pos) != null) save();
    }

    public Set<BlockPos> positions() {
        return records.keySet();
    }
}
