package com.example.addon.modules.bedaura;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bed-explosion damage/exposure math, split out of BedAura (2026-07-18, user request) --
 * mirrors 0tterware/Boze-Mint-Addon's own DamageUtils split for the same reason: this math
 * is self-contained and doesn't need BedAura's module/option state beyond the couple of
 * values passed in explicitly.
 */
public final class DamageUtils {
    private DamageUtils() {}

    /** Bed explosion power, verified via javap against BedBlock.useWithoutItem's bytecode (26.1.2). */
    private static final float BED_EXPLOSION_RADIUS = 5.0f;
    /** End crystal explosion power -- vanilla EndCrystal.onRemove calls level.explode(..., 6.0F, ...). */
    public static final float CRYSTAL_EXPLOSION_RADIUS = 6.0f;
    // "Assume Prot 4 netherite" override for estimateHpLoss's assumeBestArmor param (2026-07-19,
    // PistonCrystal port): full netherite set = 20 armor points, 4 * 3.0 toughness. Protection
    // enchant reduction is still NOT modeled here (see estimateHpLoss's javadoc) -- this only
    // overrides the armor/toughness inputs to the same un-enchanted formula, same documented
    // limitation as the rest of this class.
    private static final float ASSUMED_ARMOR = 20.0f;
    private static final float ASSUMED_TOUGHNESS = 12.0f;

    /**
     * Extrapolates {@code target}'s position {@code predictTicks} ticks ahead using its
     * current velocity. Mirrors Mint's DamageUtils.explosionDamage(predictMovement): used
     * ONLY to feed this damage/exposure estimate (position the target's hitbox will actually
     * be in when the bed detonates), never to move a placement-search cube.
     */
    private static Vec3 predictPosition(Player target, double predictTicks) {
        return target.position().add(target.getDeltaMovement().scale(predictTicks));
    }

    /**
     * Estimated HP loss (post-armor) {@code target} would take from a bed detonating at
     * {@code explosionCenter}, using MC's own ExplosionDamageCalculator formula (verified via
     * javap against ExplosionDamageCalculator.getEntityDamageAmount / CombatRules.
     * getDamageAfterAbsorb, 26.1.2): diameter = radius*2; impact = (1 - dist/diameter) *
     * exposure; damage = ((impact^2 + impact) / 2 * 7 * diameter) + 1, then armor mitigation:
     * f = 2 + armorToughness/4; g = clamp(armor - raw/f, armor*0.2, 20); result =
     * raw * (1 - g/25). Protection-enchant reduction (CombatRules.getDamageAfterMagicAbsorb)
     * is NOT applied -- EnchantmentHelper.getDamageProtection needs a ServerLevel and a real
     * DamageSource instance, neither reproducible client-side; a named simplification (worst
     * case: underestimates actual HP saved by Blast Protection gear), not a fabricated number.
     */
    public static float estimateHpLoss(Vec3 explosionCenter, Player target, double predictTicks) {
        return estimateHpLoss(explosionCenter, target, predictTicks, BED_EXPLOSION_RADIUS, false, false);
    }

    /**
     * Same formula as the 3-arg overload, generalized for PistonCrystal (2026-07-19 port):
     * {@code explosionRadius} swaps in CRYSTAL_EXPLOSION_RADIUS instead of the bed's;
     * {@code ignoreTerrain} skips the block-occlusion grid entirely (exposure=1.0) -- mirrors
     * Kallean's ExplosionUtil ignoreTerrain flag, i.e. "assume intervening blocks get destroyed
     * by the blast, don't count them as blockers"; {@code assumeBestArmor} substitutes
     * ASSUMED_ARMOR/ASSUMED_TOUGHNESS (full netherite) for the target's real armor stats into
     * the same un-enchanted formula.
     */
    public static float estimateHpLoss(Vec3 explosionCenter, Player target, double predictTicks,
                                        float explosionRadius, boolean ignoreTerrain, boolean assumeBestArmor) {
        Vec3 predictedPos = predictPosition(target, predictTicks);
        AABB predictedBox = target.getBoundingBox().move(predictedPos.subtract(target.position()));
        double seenPct = ignoreTerrain ? 1.0 : getExposure(explosionCenter, predictedBox);

        float diameter = explosionRadius * 2.0f;
        double distance = predictedPos.distanceTo(explosionCenter);
        double impact = (1.0 - distance / diameter) * seenPct;
        float raw = impact <= 0.0 ? 0.0f : (float) (((impact * impact + impact) / 2.0 * 7.0 * diameter) + 1.0);

        float armor = assumeBestArmor ? ASSUMED_ARMOR : target.getArmorValue();
        float toughness = assumeBestArmor ? ASSUMED_TOUGHNESS : (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float f = 2.0f + toughness / 4.0f;
        float g = Mth.clamp(armor - raw / f, armor * 0.2f, 20.0f);
        return raw * (1.0f - g / 25.0f);
    }

    /**
     * Dense grid over the target's real bounding box (resolution scales with box size, not a
     * fixed sample count) -- same sampling shape as Mint's getExposure/HIT_FACTORY. The
     * blocking RULE does not match Mint though (verified 2026-07-25 against real vanilla
     * source, ServerExplosion.getSeenPercent, 26.1.2 decompiled): vanilla blocks a ray on ANY
     * block with a real collision shape (ClipContext.Block.COLLIDER), never an
     * explosionResistance threshold. Mint's own >=600 rule (which this used to copy) let
     * ordinary Nether terrain (netherrack, basalt -- all far below 600) read as fully
     * transparent, so a candidate with open sky above a hole scored high exposure/damage here
     * while the real vanilla explosion is blocked by those same ordinary walls -- root cause of
     * search picking elevated/disconnected "floating" candidates whose ESTIMATED damage was far
     * higher than their measured real damage (confirmed live: search picked a spot logged at 14
     * estimated dmg, manual in-game detonation at that exact spot measured under 4 real).
     */
    private static double getExposure(Vec3 source, AABB box) {
        try {
            double xDiff = box.maxX - box.minX;
            double yDiff = box.maxY - box.minY;
            double zDiff = box.maxZ - box.minZ;

            double xStep = 1 / (xDiff * 2 + 1);
            double yStep = 1 / (yDiff * 2 + 1);
            double zStep = 1 / (zDiff * 2 + 1);
            if (xStep <= 0 || yStep <= 0 || zStep <= 0) return 0.0;

            int misses = 0, hits = 0;
            double xOffset = (1 - Math.floor(1 / xStep) * xStep) * 0.5;
            double zOffset = (1 - Math.floor(1 / zStep) * zStep) * 0.5;
            xStep *= xDiff;
            yStep *= yDiff;
            zStep *= zDiff;

            double startX = box.minX + xOffset, startY = box.minY, startZ = box.minZ + zOffset;
            double endX = box.maxX + xOffset, endY = box.maxY, endZ = box.maxZ + zOffset;

            for (double x = startX; x <= endX; x += xStep) {
                for (double y = startY; y <= endY; y += yStep) {
                    for (double z = startZ; z <= endZ; z += zStep) {
                        if (!blastBlocked(source, new Vec3(x, y, z))) misses++;
                        hits++;
                    }
                }
            }
            return hits == 0 ? 0.0 : (double) misses / hits;
        } catch (Exception e) {
            return 1.0;
        }
    }

    /** Matches vanilla ServerExplosion.getSeenPercent: ANY block with a real collision shape blocks, no resistance threshold. */
    private static boolean blastBlocked(Vec3 from, Vec3 to) {
        Minecraft mc = Minecraft.getInstance();
        return BlockGetter.traverseBlocks(from, to, Boolean.FALSE,
            (blocked, pos) -> {
                BlockState state = mc.level.getBlockState(pos);
                return state.getCollisionShape(mc.level, pos).clip(from, to, pos) != null ? Boolean.TRUE : null;
            },
            ctx -> Boolean.FALSE) == Boolean.TRUE;
    }
}
