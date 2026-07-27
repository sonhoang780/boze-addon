package com.example.addon.modules.stashfinder;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ParentOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scans every loaded chunk once, counting loot containers + storage-animal entities, and
 * fires a Discord webhook when any single type meets its configured minimum. See
 * docs/superpowers/specs (brainstorm session) for the full design discussion: min-value
 * SliderOptions grouped under Containers/Animals ParentOptions, IgnoreNatural (real
 * ServerLevel structure data in singleplayer, block-signature heuristic on multiplayer),
 * per-world persistent dedupe (StashFinderStore), Discord webhook via StashWebhook
 * (config set through the `.stashfinder` command -- Boze has no text option type).
 */
public class StashFinder extends AddonModule {
    public static final StashFinder INSTANCE = new StashFinder();

    public final ParentOption containersGroup = new ParentOption(this, "Containers",
        "Minimum count of each container type required to report a chunk.");
    // Range floor is 1, not 0 -- "min 0" reads as "0 required", which is trivially always
    // true and would spam a webhook for every chunk. Every type now always requires a
    // real count; there's no "disable this type" value anymore (matches "any single type
    // meeting its threshold reports the chunk" -- there's nothing sensible for a slider
    // to mean if it could silently always pass).
    public final SliderOption minChests = new SliderOption(this, "Chests", "", 10, 1, 64, 1, containersGroup);
    public final SliderOption minBarrels = new SliderOption(this, "Barrels", "", 10, 1, 64, 1, containersGroup);
    public final SliderOption minShulkers = new SliderOption(this, "Shulkers", "", 4, 1, 64, 1, containersGroup);
    public final SliderOption minEnderChests = new SliderOption(this, "EnderChests", "", 2, 1, 64, 1, containersGroup);
    public final SliderOption minHoppers = new SliderOption(this, "Hoppers", "", 10, 1, 64, 1, containersGroup);
    public final SliderOption minDispensersDroppers = new SliderOption(this, "DispensersDroppers", "", 10, 1, 64, 1, containersGroup);
    public final SliderOption minFurnaces = new SliderOption(this, "Furnaces", "", 10, 1, 64, 1, containersGroup);
    public final SliderOption minCrafters = new SliderOption(this, "Crafters", "", 4, 1, 64, 1, containersGroup);

    public final ParentOption animalsGroup = new ParentOption(this, "Animals",
        "Minimum count of each storage-animal type required to report a chunk.");
    public final SliderOption minDonkeys = new SliderOption(this, "Donkey", "", 2, 1, 16, 1, animalsGroup);
    public final SliderOption minLlamas = new SliderOption(this, "Llama", "", 2, 1, 16, 1, animalsGroup);
    public final SliderOption minChestBoats = new SliderOption(this, "ChestBoat", "", 1, 1, 16, 1, animalsGroup);

    public final ToggleOption ignoreNatural = new ToggleOption(this, "IgnoreNatural",
        "Ignore natural structure", true);

    private final StashFinderStore store = new StashFinderStore();
    private String lastWorldKey = null;

    // chunkKey -> ticks left before the chunk is considered fully populated and safe to scan
    private final Map<Long, Integer> pending = new java.util.HashMap<>();
    private static final int SCAN_DELAY_TICKS = 30; // ~1.5s

    private static boolean listenerRegistered = false;

    public StashFinder() {
        super("StashFinder", "Scans loaded chunks for loot stashes and reports them to Discord.");
        registerChunkListener();
    }

    private void registerChunkListener() {
        if (listenerRegistered) return;
        listenerRegistered = true;
        ClientChunkEvents.CHUNK_LOAD.register((ClientLevel level, LevelChunk chunk) -> {
            if (!INSTANCE.getState()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != level) return;

            long key = StashFinderStore.chunkKey(chunk.getPos().x(), chunk.getPos().z());
            if (store.isEvaluated(key)) return;
            pending.putIfAbsent(key, SCAN_DELAY_TICKS);
        });
    }

    // Loaded chunks near spawn/wherever the player already is (singleplayer especially --
    // the world is resident in memory) never fire CHUNK_LOAD again after their initial
    // load, which can happen long before this module is ever enabled. Relying solely on
    // the event meant those chunks were NEVER queued -- placing a fresh container right
    // in front of the player in an already-loaded chunk silently never triggered anything.
    // Bootstrap by walking a radius around the player and enqueuing every chunk that's
    // already loaded (hasChunk) but not yet evaluated, exactly like a fresh CHUNK_LOAD.
    private static final int BOOTSTRAP_RADIUS_CHUNKS = 16;

    @Override
    public void onEnable() {
        pending.clear();
        store.loadForWorld();
        lastWorldKey = StashFinderStore.currentWorldKey();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        for (int dx = -BOOTSTRAP_RADIUS_CHUNKS; dx <= BOOTSTRAP_RADIUS_CHUNKS; dx++) {
            for (int dz = -BOOTSTRAP_RADIUS_CHUNKS; dz <= BOOTSTRAP_RADIUS_CHUNKS; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                long key = StashFinderStore.chunkKey(cx, cz);
                if (store.isEvaluated(key)) continue;
                pending.putIfAbsent(key, SCAN_DELAY_TICKS);
            }
        }
    }

    private void maybeReloadStoreForWorld() {
        String key = StashFinderStore.currentWorldKey();
        if (!key.equals(lastWorldKey)) {
            store.loadForWorld();
            lastWorldKey = key;
            pending.clear();
        }
    }

    // Fast travel (nether highways, ~33 blocks/s) enqueues chunks in bursts that all
    // expire on the same tick; evaluating them all at once (container count + possible
    // full-column structure scan each) stalled the frame. Budget per tick instead --
    // 4/tick comfortably outpaces the ~1.7 chunks/tick arrival rate at that speed.
    private static final int MAX_EVALS_PER_TICK = 4;

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        maybeReloadStoreForWorld();
        store.flushIfStale();
        if (pending.isEmpty()) return;

        int budget = MAX_EVALS_PER_TICK;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            if (budget == 0) {
                e.setValue(1); // budget spent -- re-eligible next tick
                continue;
            }
            budget--;
            it.remove();
            long key = e.getKey();
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >>> 32);
            evaluateChunk(mc, chunkX, chunkZ, key);
        }
    }

    @Override
    public void onDisable() {
        store.flush();
    }

    /** `.stashfinder reset` -- wipe this world's dedupe so already-visited stashes report again. */
    public void resetCurrentWorldStore() {
        store.loadForWorld();
        store.clearAll();
        pending.clear();
        lastWorldKey = StashFinderStore.currentWorldKey();
    }

    private void evaluateChunk(Minecraft mc, int chunkX, int chunkZ, long key) {
        if (!mc.level.hasChunk(chunkX, chunkZ)) return; // unloaded again before delay elapsed
        LevelChunk chunk = mc.level.getChunk(chunkX, chunkZ);

        store.markEvaluated(key); // never re-scan this chunk again, regardless of outcome

        // Container/entity counting is cheap (block-entity map + entity-in-AABB lookup,
        // both bounded by what's actually present). The multiplayer structure-signature
        // fallback is NOT cheap -- it walks every block in the chunk column (16x16x~384 =
        // ~98k getBlockState calls). Running that on every single loaded chunk (the
        // previous order) is exactly what caused the FPS drop crossing into new terrain.
        // Check the cheap threshold FIRST; the expensive structure scan only runs on the
        // rare chunk that already looks like a real stash candidate.
        Map<String, Integer> counts = countContainers(chunk);
        counts.putAll(countAnimals(mc, chunk));

        if (!meetsAnyThreshold(counts)) return;
        if (ignoreNatural.getValue() && StructureSignature.isNatural(mc, chunk)) return;

        Map<String, Integer> nonZero = new LinkedHashMap<>();
        for (var e : counts.entrySet()) {
            if (e.getValue() > 0) nonZero.put(e.getKey(), e.getValue());
        }

        ChunkPos pos = chunk.getPos();
        int reportX = pos.getMinBlockX();
        int reportZ = pos.getMinBlockZ();
        String dimension = mc.level.dimension().identifier().toString();
        StashWebhook.send(reportX, reportZ, dimension, nonZero);
    }

    private boolean meetsAnyThreshold(Map<String, Integer> counts) {
        return check(counts, "Chests", minChests)
            || check(counts, "Barrels", minBarrels)
            || check(counts, "Shulkers", minShulkers)
            || check(counts, "Ender Chests", minEnderChests)
            || check(counts, "Hoppers", minHoppers)
            || check(counts, "Dispensers/Droppers", minDispensersDroppers)
            || check(counts, "Furnaces", minFurnaces)
            || check(counts, "Crafters", minCrafters)
            || check(counts, "Donkey", minDonkeys)
            || check(counts, "Llama", minLlamas)
            || check(counts, "Chest Boat", minChestBoats);
    }

    private boolean check(Map<String, Integer> counts, String key, SliderOption min) {
        int threshold = min.getValue().intValue();
        if (threshold <= 0) return false; // 0 = don't care about this type
        return counts.getOrDefault(key, 0) >= threshold;
    }

    private Map<String, Integer> countContainers(LevelChunk chunk) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int chests = 0, barrels = 0, shulkers = 0, enderChests = 0, hoppers = 0,
            dispensersDroppers = 0, furnaces = 0, crafters = 0;

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity) chests++;
            else if (be instanceof BarrelBlockEntity) barrels++;
            else if (be instanceof ShulkerBoxBlockEntity) shulkers++;
            else if (be instanceof EnderChestBlockEntity) enderChests++;
            else if (be instanceof HopperBlockEntity) hoppers++;
            else if (be instanceof DispenserBlockEntity || be instanceof DropperBlockEntity) dispensersDroppers++;
            else if (be instanceof FurnaceBlockEntity || be instanceof BlastFurnaceBlockEntity
                || be instanceof SmokerBlockEntity) furnaces++;
            else if (be instanceof CrafterBlockEntity) crafters++;
        }

        counts.put("Chests", chests);
        counts.put("Barrels", barrels);
        counts.put("Shulkers", shulkers);
        counts.put("Ender Chests", enderChests);
        counts.put("Hoppers", hoppers);
        counts.put("Dispensers/Droppers", dispensersDroppers);
        counts.put("Furnaces", furnaces);
        counts.put("Crafters", crafters);
        return counts;
    }

    private Map<String, Integer> countAnimals(Minecraft mc, LevelChunk chunk) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int donkeys = 0, llamas = 0, chestBoats = 0;

        ChunkPos pos = chunk.getPos();
        AABB box = new AABB(pos.getMinBlockX(), mc.level.getMinY(), pos.getMinBlockZ(),
            pos.getMaxBlockX() + 1, mc.level.getMaxY(), pos.getMaxBlockZ() + 1);

        for (var entity : mc.level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> true)) {
            if (entity instanceof Donkey) donkeys++;
            else if (entity instanceof Llama) llamas++;
            else if (entity instanceof ChestBoat) chestBoats++;
        }

        counts.put("Donkey", donkeys);
        counts.put("Llama", llamas);
        counts.put("Chest Boat", chestBoats);
        return counts;
    }
}
