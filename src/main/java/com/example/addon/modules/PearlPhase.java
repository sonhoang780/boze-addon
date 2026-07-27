package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInteract;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.MathHelper;
import dev.boze.api.utility.interaction.BreakHelper;
import dev.boze.api.utility.interaction.Interaction;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Port of Homovore's dev.leonetic.features.modules.combat.PhaseModule onto boze-api.
 * <p>
 * Homovore drives the throw from EventTick + RotationManager/SwapManager, neither of which
 * exists in boze-api; the entry point here is EventInteract (BreakHelper's javadoc states breaks
 * are only anticheat-compliant "when run from an interaction", and staying off EventTick avoids
 * that class of silent failure entirely).
 * <p>
 * The actual throw packet, though, is sent the same way PhaseModule sends it:
 * RotationManager.performSilent() proves the reference never trusts the live player rotation for
 * the outgoing packet -- it sends yaw/pitch as explicit ServerboundMovePlayerPacket.PosRot args,
 * and PhaseModule's own ServerboundUseItemPacket constructor call passes yaw/pitch directly too.
 * boze's Interaction(action, yaw, pitch) was tried as the rotation source instead and measured
 * broken: mc.gameMode.useItem(player, hand) builds its packet from the player's LIVE
 * getYRot()/getXRot(), so whatever Interaction does to the player's rotation field either doesn't
 * happen or doesn't stick before that read -- the pearl kept flying at the real camera pitch.
 * Fix: build ServerboundUseItemPacket manually with the computed yaw/pitch as constructor args,
 * exactly like PhaseModule, using the same vanilla ClientLevel.getBlockStatePredictionHandler()
 * PhaseModule's own mixin wraps (public API, no mixin needed here).
 * <p>
 * Target/yaw/pitch math (calculateTargetPos/calcYaw, CORNER_THRESHOLD/CORNER_OFFSET, the
 * toClosest diagonal-seam snap, the fixed 85/75 pitch bucket) is copied as-is from PhaseModule.
 * Three additions kept from the pre-existing boze version: submitScaffoldingBreak (breaks
 * scaffolding at/under the feet first, since PhaseModule assumes solid ground), the Crawl
 * toggle (retargets Y to the block's bottom edge instead of PhaseModule's playerY-0.5, needed to
 * throw while in the crawling pose), and submitHazardPlace (NCP only -- silently places a
 * web/flint&amp;steel under the feet before throwing, since NCP's clip check wants a block there).
 */
public class PearlPhase extends AddonModule {
    public static final PearlPhase INSTANCE = new PearlPhase();

    private static final double CORNER_THRESHOLD = 0.5;
    private static final double CORNER_OFFSET = 0.5;
    // EnderpearlItem#use launch speed, passed to shootFromRotation (javap, 26.1.2 deobf jar).
    private static final double LAUNCH_SPEED = 1.5;

    public final ModeOption<InteractionMode> anticheat = new ModeOption<>(this, "AntiCheat",
        "Anticheat handler used for the scaffolding break and the pearl throw.", InteractionMode.Grim);

    public final ModeOption<SwapType> swap = new ModeOption<>(this, "Swap",
        "Silent/Alt/Normal -- how to swap to the pearl before throwing it.", SwapType.Silent);

    public final ToggleOption crawl = new ToggleOption(this, "Crawl",
        "Aim at the bottom edge of the block underfoot instead of playerY-0.5 -- "
        + "needed to phase while in the crawling pose (Folia only, vanilla blocks it either way).", false);

    private boolean thrown = false;
    private boolean hazardPlaced = false;

    private PearlPhase() {
        super("PearlPhase", "Throw an ender pearl to phase/clip into the block under your feet. Runs once then auto-disables.");
    }

    @Override
    public void onEnable() {
        thrown = false;
        hazardPlaced = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) setState(false);
    }

    @EventHandler
    private void onInteract(EventInteract event) {
        if (event.getMode() != anticheat.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) { setState(false); return; }

        // Disabling one fire later (rather than inside the throw's own Runnable) keeps
        // setState -- which unsubscribes this module from the event bus -- out of the bus's
        // own dispatch/execution pass.
        if (thrown) { setState(false); return; }

        if (submitScaffoldingBreak(event, mc)) return;
        if (anticheat.getValue() == InteractionMode.NCP && submitHazardPlace(event, mc)) return;

        if (mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) { setState(false); return; }
        if (mc.player.isCrouching()) { setState(false); return; }

        int slot = InvHelper.findInHotbar(Items.ENDER_PEARL);
        if (slot == -1) slot = InvHelper.find(Items.ENDER_PEARL);
        if (slot == -1) { setState(false); return; }

        Vec3 target = calculateTargetPos(mc);
        float[] rot = compensateInheritedVelocity(mc,
            calcYaw(mc, target),
            mc.player.getBlockY() > 4 ? 85f : 75f);
        float yaw = rot[0];
        float pitch = rot[1];

        final int useSlot = slot;
        final float useYaw = yaw;
        final float usePitch = pitch;
        thrown = true;
        event.addInteraction(new Interaction(() -> {
            InvHelper.swapToSlot(useSlot, swap.getValue());
            // PhaseModule sends the use-item packet with yaw/pitch as explicit constructor args
            // instead of calling gameMode.useItem(player, hand) -- that vanilla call reads the
            // player's LIVE getYRot()/getXRot() to build the packet, not the Interaction's forced
            // rotation, so the pearl went wherever the real camera pointed.
            try (var handler = predictionHandler(mc.level).startPredicting()) {
                mc.getConnection().send(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, handler.currentSequence(), useYaw, usePitch));
            }
            InvHelper.swapBack();
        }, yaw, pitch));
    }

    // ClientLevel#getBlockStatePredictionHandler() is package-private; PhaseModule reaches it
    // via a Homovore mixin accessor, boze-api exposes none, so reflection is the only path.
    private static final java.lang.reflect.Method GET_PREDICTION_HANDLER;
    static {
        try {
            GET_PREDICTION_HANDLER = ClientLevel.class.getDeclaredMethod("getBlockStatePredictionHandler");
            GET_PREDICTION_HANDLER.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler predictionHandler(net.minecraft.world.level.Level level) {
        try {
            return (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler) GET_PREDICTION_HANDLER.invoke(level);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Scaffolding is climbable, so the player stands INSIDE it as often as on top of it --
     * checking only the block below (the old behavior) missed the standing-inside case
     * entirely. Returns true once a break is queued, so the caller retries next fire until
     * the block is actually gone.
     */
    private boolean submitScaffoldingBreak(EventInteract event, Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        for (BlockPos pos : new BlockPos[] { feet, feet.below() }) {
            if (!mc.level.getBlockState(pos).is(Blocks.SCAFFOLDING)) continue;
            // throughWalls=true: the block you are standing in or on routinely fails the
            // line-of-sight check the default overload requires.
            Interaction breakIt = BreakHelper.interaction(pos, true, mc.player.blockInteractionRange(), true);
            if (breakIt == null) continue;
            event.addInteraction(breakIt);
            return true;
        }
        return false;
    }

    /** NCP only. Runs at most once per enable so a place that never registers can't stall the throw. */
    private boolean submitHazardPlace(EventInteract event, Minecraft mc) {
        if (hazardPlaced) return false;

        BlockPos pos = mc.player.blockPosition();
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;

        boolean web = true;
        int slot = InvHelper.findInHotbar(Items.COBWEB);
        if (slot == -1) { web = false; slot = InvHelper.findInHotbar(Items.FLINT_AND_STEEL); }
        if (slot == -1) return false;

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos.below(), false);
        float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), hit.getLocation());
        final boolean useWeb = web;
        final int useSlot = slot;

        hazardPlaced = true;
        event.addInteraction(new Interaction(() -> {
            InvHelper.swapToSlot(useSlot, SwapType.Silent);
            if (useWeb) {
                PlaceHelper.place(anticheat.getValue(), hit, InteractionHand.MAIN_HAND);
            } else {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            }
            InvHelper.swapBack();
        }, rot[0], rot[1]));
        return true;
    }

    private Vec3 calculateTargetPos(Minecraft mc) {
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        double nearestIntX = Math.round(playerX);
        double nearestIntZ = Math.round(playerZ);
        double dxCorner = nearestIntX - playerX;
        double dzCorner = nearestIntZ - playerZ;

        double y = crawl.getValue() ? mc.player.blockPosition().below().getY() : mc.player.getY() - 0.5;

        if (Math.abs(dxCorner) <= CORNER_THRESHOLD && Math.abs(dzCorner) <= CORNER_THRESHOLD) {
            return new Vec3(
                playerX + Mth.clamp(dxCorner, -CORNER_OFFSET, CORNER_OFFSET),
                y,
                playerZ + Mth.clamp(dzCorner, -CORNER_OFFSET, CORNER_OFFSET)
            );
        }

        final double A = Math.PI / 13;
        final double B = Math.PI / 4;

        double x = playerX + Mth.clamp(
            toClosest(playerX, Math.floor(playerX) + A, Math.floor(playerX) + B) - playerX,
            -0.2, 0.2);
        double z = playerZ + Mth.clamp(
            toClosest(playerZ, Math.floor(playerZ) + A, Math.floor(playerZ) + B) - playerZ,
            -0.2, 0.2);

        return new Vec3(x, y, z);
    }

    private double toClosest(double num, double min, double max) {
        return (num - min) > (max - num) ? max : min;
    }

    private float calcYaw(Minecraft mc, Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 diff = target.subtract(eye);
        return (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
    }

    /**
     * Projectile#shootFromRotation adds the thrower's getKnownMovement() on top of the
     * LAUNCH_SPEED aim vector -- x/z always, y only while airborne (javap, 26.1.2 deobf jar).
     * A near-vertical throw carries only ~0.13 horizontal, so sprint-strafing (~0.28/tick)
     * more than doubles it sideways and the pearl clips a neighbouring block's face instead
     * of the one underfoot.
     * <p>
     * Rotate the aim so the sum still runs along the intended ray: solve |k*d - v| =
     * LAUNCH_SPEED for k, aim at k*d - v. At zero velocity k == LAUNCH_SPEED and the result is
     * the uncompensated aim, so standing-still throws are unchanged.
     */
    private float[] compensateInheritedVelocity(Minecraft mc, float yaw, float pitch) {
        Vec3 aimDir = rotationVector(yaw, pitch);
        Vec3 move = mc.player.getDeltaMovement();
        Vec3 inherited = new Vec3(move.x, mc.player.onGround() ? 0.0 : move.y, move.z);
        if (inherited.lengthSqr() < 1.0E-8) return new float[] { yaw, pitch };

        double dot = aimDir.dot(inherited);
        double disc = dot * dot + LAUNCH_SPEED * LAUNCH_SPEED - inherited.lengthSqr();
        if (disc < 0) return new float[] { yaw, pitch }; // outrunning the pearl -- uncorrectable

        Vec3 aim = aimDir.scale(dot + Math.sqrt(disc)).subtract(inherited);
        double horizontal = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-aim.x, aim.z)),
            (float) Math.toDegrees(Math.atan2(-aim.y, horizontal))
        };
    }

    private static Vec3 rotationVector(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double cosPitch = Math.cos(p);
        return new Vec3(-Math.sin(y) * cosPitch, -Math.sin(p), Math.cos(y) * cosPitch);
    }
}
