package com.example.addon.modules.stashfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Map;

/**
 * IgnoreNatural detection, two paths depending on where the world actually lives:
 *
 * Singleplayer: the integrated server runs in this same JVM, so the REAL per-chunk
 * structure-start data (ChunkAccess.getAllStarts()) is reachable straight off the
 * server's own ChunkAccess -- exact, no guessing, covers every structure including
 * Desert Pyramid.
 *
 * Multiplayer: structure-start data is server-internal worldgen state and is never
 * sent to the client (verified via javap: ChunkAccess.setAllStarts() is only ever
 * called from the ProtoChunk->LevelChunk server-side conversion path, never from any
 * network packet deserialization). So on a remote server there is nothing to query --
 * fall back to a block-signature heuristic covering the structures likeliest to produce
 * a container-heavy false positive.
 */
public class StructureSignature {

    private static final int SIGNATURE_HITS_REQUIRED = 6;

    public static boolean isNatural(Minecraft mc, LevelChunk chunk) {
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null && mc.level != null) {
            ServerLevel serverLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());
            if (serverLevel != null) {
                ChunkPos pos = chunk.getPos();
                ChunkAccess serverChunk = serverLevel.getChunk(pos.x(), pos.z());
                if (serverChunk != null) {
                    for (Map.Entry<?, StructureStart> e : serverChunk.getAllStarts().entrySet()) {
                        if (e.getValue() != null && e.getValue().isValid()) return true;
                    }
                    // getAllStarts() only holds data in a structure's ORIGIN chunk. Every
                    // other chunk the structure spans (e.g. a pyramid's outer chunks) only
                    // carries a reference to that origin chunk, not a StructureStart -- so
                    // standing inside a large structure but off its origin chunk fell
                    // through to "natural" and reported the chunk as a stash.
                    for (var refs : serverChunk.getAllReferences().values()) {
                        if (refs != null && !refs.isEmpty()) return true;
                    }
                    return false;
                }
            }
        }
        return matchesHeuristic(chunk);
    }

    // Multiplayer fallback: count blocks unique/near-unique to each structure's palette,
    // but only inside the Y band where that structure actually generates, and only if
    // the chunk's biome is one the structure can spawn in (vanilla HAS_* biome tags are
    // registry-synced to the client, so Holder#is works on remote servers). Biome + Y
    // together kill the classic false positives: a terracotta-clad player base in plains
    // no longer reads as a Desert Pyramid, and dark-oak builds above ground don't count
    // toward Ancient City sculk found at y -52.
    // Walks LevelChunkSection directly (local 0-15 coords, no BlockPos allocation) and
    // skips air-only sections and sections fully outside every signature's Y band. Only
    // reached for chunks that already met a count threshold (rare), so this is not a
    // per-loaded-chunk cost.
    private static boolean matchesHeuristic(LevelChunk chunk) {
        int[] counts = new int[Sig.values().length];
        var sections = chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            int bottomY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int ly = 0; ly < 16; ly++) {
                int y = bottomY + ly;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        Block block = section.getBlockState(lx, ly, lz).getBlock();
                        for (Sig sig : Sig.values()) {
                            if (y >= sig.yMin && y <= sig.yMax && sig.blocks.contains(block)) {
                                counts[sig.ordinal()]++;
                            }
                        }
                    }
                }
            }
        }

        for (Sig sig : Sig.values()) {
            if (counts[sig.ordinal()] >= sig.threshold && chunkHasBiome(chunk, sig)) return true;
        }
        return false;
    }

    // Sample 5 quart columns (4 corners + center) at the middle of the signature's Y
    // band -- biomes are stored per 4x4x4 quart, so this covers the chunk edge-to-edge.
    private static boolean chunkHasBiome(LevelChunk chunk, Sig sig) {
        ChunkPos pos = chunk.getPos();
        int midY = Math.clamp((sig.yMin + sig.yMax) / 2, chunk.getMinY(), chunk.getMaxY());
        int qy = midY >> 2;
        int minQX = pos.getMinBlockX() >> 2, minQZ = pos.getMinBlockZ() >> 2;
        int[][] samples = { {0, 0}, {3, 0}, {0, 3}, {3, 3}, {2, 2} };
        for (int[] s : samples) {
            if (chunk.getNoiseBiome(minQX + s[0], qy, minQZ + s[1]).is(sig.biomeTag)) return true;
        }
        return false;
    }

    private enum Sig {
        TRIAL_CHAMBERS(java.util.Set.of(Blocks.VAULT, Blocks.TRIAL_SPAWNER), 1,
            BiomeTags.HAS_TRIAL_CHAMBERS, -60, 20),
        ANCIENT_CITY(java.util.Set.of(Blocks.REINFORCED_DEEPSLATE, Blocks.SCULK,
            Blocks.SCULK_CATALYST), 8, BiomeTags.HAS_ANCIENT_CITY, -64, -10),
        BASTION_REMNANT(java.util.Set.of(Blocks.GILDED_BLACKSTONE, Blocks.BLACKSTONE), 40,
            BiomeTags.HAS_BASTION_REMNANT, 0, 128),
        OCEAN_MONUMENT(java.util.Set.of(Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS,
            Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN), 40,
            BiomeTags.HAS_OCEAN_MONUMENT, 30, 65),
        WOODLAND_MANSION(java.util.Set.of(Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LOG,
            Blocks.COBBLESTONE), 200, BiomeTags.HAS_WOODLAND_MANSION, 50, 140),
        DESERT_PYRAMID(java.util.Set.of(Blocks.ORANGE_TERRACOTTA, Blocks.BLUE_TERRACOTTA,
            Blocks.CYAN_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA), SIGNATURE_HITS_REQUIRED,
            BiomeTags.HAS_DESERT_PYRAMID, 40, 90);

        final java.util.Set<Block> blocks;
        final int threshold;
        final TagKey<Biome> biomeTag;
        final int yMin, yMax;

        Sig(java.util.Set<Block> blocks, int threshold, TagKey<Biome> biomeTag, int yMin, int yMax) {
            this.blocks = blocks;
            this.threshold = threshold;
            this.biomeTag = biomeTag;
            this.yMin = yMin;
            this.yMax = yMax;
        }
    }
}
