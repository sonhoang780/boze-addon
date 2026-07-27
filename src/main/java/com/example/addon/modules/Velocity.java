package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * Velocity: NoPush toggles (real physics via MixinEntity/MixinLocalPlayer) + incoming-knockback
 * cancel ported from Gurtex-Phobos's Velocity module (org.phobos...DCbjVQQ5joZkhAF2, decompiled),
 * modes Normal and Grim only.
 *
 * Phobos maps knockback and explosion to full-cancel for both these ordinals (enum $VALUES order
 * Normal=0, Grim=1 -> both fall through to setCancelled(true) in the SetEntityMotion listener):
 * - Knockback: server sends ClientboundSetEntityMotionPacket for the local player; cancelling it
 *   on receive means the client never applies the knockback, so movement stays full-speed/smooth
 *   (no counter-strafe trickery needed). Guard mirrors Phobos: skip while fall-flying + holding a
 *   firework so an elytra boost isn't eaten.
 * - Explosion: 26.1.2's ClientboundExplodePacket is an immutable record carrying Optional<Vec3>
 *   playerKnockback (verified via javap); Phobos zeroed that field through a mutator mixin, but
 *   the record can't be mutated from the receive event, so this cancels the whole packet when it
 *   carries knockback. Trade-off: the local explosion particle/sound and client-side block-break
 *   for that packet are skipped too (blocks self-heal on the next server block update). Add a
 *   ClientboundExplodePacket accessor mixin later if preserving those visuals matters.
 *
 * Difference between the two modes:
 * - Normal: cancel only. Works against no-AC / NCP-style servers that trust the client dropping
 *   the packet. On GrimAC it would flag (Grim re-simulates and expects the knockback).
 * - Grim: cancel PLUS, on the tick after taking velocity, inject an "invalid" packet pair Phobos
 *   uses to abuse Grim's uncertainty window -- a duplicate PosRot move (redundant position) plus a
 *   START_DESTROY_BLOCK dig on an air block at the feet/head. This inflates Grim's tolerated offset
 *   enough that the dropped knockback stays within it. Gated by Filter (only fire right after
 *   actually taking velocity), matching Phobos's `filter && !velocity -> return`.
 */
public class Velocity extends AddonModule {
    public static final Velocity INSTANCE = new Velocity();

    public enum Mode { Off, Normal, Grim }

    public final ModeOption<Mode> mode = new ModeOption<>(this, "Mode",
            "Off: no velocity handling. Normal: cancel incoming knockback (no-AC/NCP). Grim: "
            + "cancel + inject Grim uncertainty-abuse packets. Ported from Phobos.", Mode.Off);
    public final ToggleOption knockback = new ToggleOption(this, "Knockback",
            "Cancel the entity-velocity knockback packet.", true);
    public final ToggleOption explosions = new ToggleOption(this, "Explosions",
            "Cancel incoming explosion knockback (crystals/anchors/TNT).", true);
    public final ToggleOption filter = new ToggleOption(this, "Filter",
            "Grim: only send the invalid packets right after taking velocity.", true);

    public final SliderOption burstLimit = new SliderOption(this, "Burst Limit",
            "Full-cancel up to this many hits/sec; past it, let some knockback through so a "
            + "crystal-aura burst doesn't drift past the server's movement tolerance and trigger "
            + "repeated resync-teleport stutter.", 3.0, 1.0, 10.0, 1.0);
    public final SliderOption burstReducePercent = new SliderOption(this, "Burst Reduce %",
            "How much of the knockback to let through once Burst Limit is exceeded.", 35.0, 0.0, 100.0, 5.0);

    public final ToggleOption noPushEntities = new ToggleOption(this, "NoPush-Entities",
            "Cancel the vanilla entity-collision bump (real physics, via MixinEntity#push).", true);
    public final ToggleOption noPushFluids = new ToggleOption(this, "NoPush-Liquids",
            "Ignore flowing-liquid drag (real physics, via MixinEntity#isPushedByFluid).", true);
    public final ToggleOption noPushBlocks = new ToggleOption(this, "NoPush-Blocks",
            "Cancel the nudge LocalPlayer applies when squeezed inside a solid block (real "
            + "Mojmap name moveTowardsClosestSpace, Yarn's pushOutOfBlocks -- verified present "
            + "on 26.1.2 via javap/strings, not a guess).", true);

    // Set on the packet thread when knockback/explosion is taken, read+cleared on the client tick
    // by the Grim inject. Volatile for the cross-thread handoff.
    private volatile boolean tookVelocity;
    // Packet thread only (EventPacket.Receive isn't concurrent with itself), no lock needed.
    private final ArrayDeque<Long> recentCancels = new ArrayDeque<>();

    public Velocity() {
        super("Velocity", "NoPush toggles + incoming-knockback cancel (Normal/Grim, ported from Phobos).");
    }

    @Override
    public void onEnable() { tookVelocity = false; recentCancels.clear(); }

    /**
     * Full-cancelling every hit is what causes the "few crystals = smooth, many crystals = stutter"
     * split: a couple of dropped knockbacks stay under vanilla's per-tick movement-tolerance check,
     * but a crystal-aura burst compounds the client/server position drift past it, and the server's
     * own anti-desync kicks in with repeated hard-teleport resyncs -- that resync spam IS the
     * observed freeze/jitter, not an addon hang. Past burstLimit hits/sec, let a fraction of the
     * real knockback through to keep drift inside the server's tolerance.
     */
    private void applyBurstFallback(Minecraft mc, Vec3 full) {
        long now = System.currentTimeMillis();
        recentCancels.addLast(now);
        while (!recentCancels.isEmpty() && now - recentCancels.peekFirst() > 1000) recentCancels.pollFirst();
        if (recentCancels.size() <= burstLimit.getValue().intValue()) return;

        Vec3 partial = full.scale(burstReducePercent.getValue() / 100.0);
        mc.player.setDeltaMovement(mc.player.getDeltaMovement().add(partial));
    }

    /** Don't eat an elytra firework boost (Phobos: skip when fall-flying AND holding a rocket). */
    private boolean fallFlyingBoost(Minecraft mc) {
        return mc.player.isFallFlying() && mc.player.getMainHandItem().getItem() == Items.FIREWORK_ROCKET;
    }

    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (mode.getValue() == Mode.Off) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (event.packet instanceof ClientboundSetEntityMotionPacket p) {
            if (!knockback.getValue()) return;
            if (p.id() != mc.player.getId()) return;
            if (fallFlyingBoost(mc)) return;
            tookVelocity = true;
            event.cancel();
            applyBurstFallback(mc, p.movement());
        } else if (event.packet instanceof ClientboundExplodePacket p) {
            if (!explosions.getValue()) return;
            if (p.playerKnockback().isEmpty()) return;
            tookVelocity = true;
            event.cancel();
            applyBurstFallback(mc, p.playerKnockback().get());
        }
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        if (mode.getValue() != Mode.Grim) { tookVelocity = false; return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        // Filter on: only inject right after taking velocity. Filter off: inject every tick.
        if (filter.getValue() && !tookVelocity) return;

        BlockPos foot = mc.player.blockPosition();
        BlockPos head = foot.above();
        boolean footAir = mc.level.getBlockState(foot).isAir();
        boolean headAir = mc.level.getBlockState(head).isAir();
        if (!footAir && !headAir) return; // need an air cell to fake a dig against
        BlockPos target = footAir ? foot : head;

        // Duplicate PosRot move (redundant position) + a dig on air: the packet pair Phobos sends
        // to widen Grim's uncertainty. Sent straight through the connection, so no local
        // block-break/movement state is touched.
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.position(), mc.player.getYRot(), mc.player.getXRot(),
                mc.player.onGround(), mc.player.horizontalCollision));
        mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, target, Direction.DOWN));
        tookVelocity = false;
    }
}
