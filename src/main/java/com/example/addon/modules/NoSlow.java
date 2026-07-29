package com.example.addon.modules;

import com.example.addon.mixin.ClientLevelPredictionInvoker;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Literal port of ThunderHack-Reborn's NoSlow (thunder.hack.features.modules.movement.NoSlow,
 * StormDevzz/ThunderHack-Reborn, main branch) onto real MC 26.1.2 / Boze API. Every Yarn name
 * below was cross-checked against javap on minecraft-merged-deobf-26.1.2.jar (jdk-25) before
 * porting -- see the per-mode notes for the mapping:
 *
 * PlayerInteractItemC2SPacket -> ServerboundUseItemPacket(InteractionHand, int sequence,
 *   float yRot, float xRot) -- confirmed identical constructor shape.
 * UpdateSelectedSlotC2SPacket -> ServerboundSetCarriedItemPacket(int).
 * PlayerActionC2SPacket -> ServerboundPlayerActionPacket(Action, BlockPos, Direction[, int
 *   sequence]) -- Action.ABORT_DESTROY_BLOCK / SWAP_ITEM_WITH_OFFHAND confirmed present.
 * Hand -> InteractionHand. mc.player.input.movementVector (float Vec2, mutable pre-physics)
 *   has no equivalent hook here: Boze's only input event (EventInput) exposes boolean
 *   forward/backward/left/right tags, not a scalable float vector. Matrix3 is ported instead
 *   as a Mixin override of LocalPlayer#modifyInput(Vec2)'s return value (see
 *   MixinLocalPlayer#noSlow$matrix3) -- same real private method itemUseSpeedMultiplier's
 *   0.2x normally flows through, verified via javap.
 *
 * Grim/GrimNew's fake offhand ServerboundUseItemPacket needs a real sequence id: Grim's
 * BadPacketsH (grim.badpackets.unexpected_sequence) requires every use-item/block-place/
 * block-break sequence to be exactly lastSequence+1 in ONE shared stream server-side. An
 * independent local counter would desync from that stream and flag instantly -- so
 * mintSequence() below reaches into ClientLevel's real BlockStatePredictionHandler (via
 * ClientLevelPredictionInvoker, since the getter is package-private) and mints from the SAME
 * counter MultiPlayerGameMode's real useItem()/destroyBlock() calls use.
 */
public class NoSlow extends AddonModule {
    public static final NoSlow INSTANCE = new NoSlow();

    public enum Mode { NCP, StrictNCP, Matrix, Grim, MusteryGrief, GrimNew, Matrix2, LFCraft, Matrix3, Skip }

    public final ModeOption<Mode> mode = new ModeOption<>(this, "Mode",
            "NCP: no-op, vanilla slowdown applies. Others: per-anticheat bypass, ported from "
            + "ThunderHack-Reborn's NoSlow.", Mode.NCP);

    public final ToggleOption mainHand = new ToggleOption(this, "MainHand",
            "Grim/GrimNew: also cover eating with the main hand, not just the offhand.", true);

    public final ToggleOption food = new ToggleOption(this, "Food",
            "Allow NoSlow while eating/drinking (FOOD data component present).", true);
    public final ToggleOption projectiles = new ToggleOption(this, "Projectiles",
            "Allow NoSlow while drawing a bow/crossbow/trident.", true);
    public final ToggleOption shield = new ToggleOption(this, "Shield",
            "Allow NoSlow while blocking with a shield.", true);
    public final ToggleOption soulSand = new ToggleOption(this, "SoulSand", "", true);
    public final ToggleOption honey = new ToggleOption(this, "Honey", "", true);
    public final ToggleOption slime = new ToggleOption(this, "Slime", "", true);
    public final ToggleOption ice = new ToggleOption(this, "Ice", "", true);
    public final ToggleOption sweetBerryBush = new ToggleOption(this, "SweetBerryBush", "", true);
    public final ToggleOption sneak = new ToggleOption(this, "Sneak", "", false);
    public final ToggleOption crawl = new ToggleOption(this, "Crawl", "", false);

    private boolean returnSneak;

    public NoSlow() {
        super("NoSlow", "Move at full speed while using an item -- per-anticheat bypass ported from ThunderHack-Reborn.");
    }

    private void send(Minecraft mc, Packet<?> packet) {
        if (mc.getConnection() != null) mc.getConnection().send(packet);
    }

    /**
     * Ported from Kallean's NoSlowModule#checkStack: Grim's fake OFF_HAND use-item packet
     * (sent while genuinely eating with MAIN_HAND) only stays a harmless illusion if the
     * offhand's real item wouldn't itself react to a real use-item call. A real food/shield
     * item there would actually start eating/blocking client-side off the fake packet,
     * desyncing state instead of hiding it. Bow/crossbow are excluded for the same reason
     * (would start a real draw).
     */
    private boolean checkStack(ItemStack stack) {
        return !stack.has(DataComponents.FOOD) && stack.getItem() != Items.BOW
                && stack.getItem() != Items.CROSSBOW && stack.getItem() != Items.SHIELD;
    }

    /** Mints a real, in-sequence id from ClientLevel's own BlockStatePredictionHandler -- see class javadoc. */
    private int mintSequence(Minecraft mc) {
        BlockStatePredictionHandler handler = ((ClientLevelPredictionInvoker) mc.level).invokeGetBlockStatePredictionHandler();
        try (BlockStatePredictionHandler started = handler.startPredicting()) {
            return started.currentSequence();
        }
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        if (returnSneak) {
            mc.options.keyShift.setDown(false);
            mc.player.setSprinting(true);
            returnSneak = false;
        }

        if (!getState()) return;
        if (!mc.player.isUsingItem() || mc.player.isPassenger() || mc.player.isFallFlying()) return;

        switch (mode.getValue()) {
            case StrictNCP -> send(mc, new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
            case MusteryGrief -> {
                if (mc.player.onGround() && mc.options.keyJump.isDown()) {
                    mc.options.keyShift.setDown(true);
                    returnSneak = true;
                }
            }
            case Grim -> {
                if (mc.player.getUsedItemHand() == InteractionHand.OFF_HAND) {
                    int selected = mc.player.getInventory().getSelectedSlot();
                    send(mc, new ServerboundSetCarriedItemPacket(selected % 8 + 1));
                    send(mc, new ServerboundSetCarriedItemPacket(selected % 7 + 2));
                    send(mc, new ServerboundSetCarriedItemPacket(selected));
                } else if (mainHand.getValue() && checkStack(mc.player.getOffhandItem())) {
                    send(mc, new ServerboundUseItemPacket(InteractionHand.OFF_HAND, mintSequence(mc),
                            mc.player.getYRot(), mc.player.getXRot()));
                }
            }
            case Matrix -> {
                Vec3 v = mc.player.getDeltaMovement();
                if (mc.player.onGround() && !mc.options.keyJump.isDown()) {
                    mc.player.setDeltaMovement(v.x * 0.3, v.y, v.z * 0.3);
                } else if (mc.player.fallDistance > 0.2f) {
                    mc.player.setDeltaMovement(v.x * 0.95, v.y, v.z * 0.95);
                }
            }
            case GrimNew -> {
                if (mc.player.getUsedItemHand() == InteractionHand.OFF_HAND) {
                    int selected = mc.player.getInventory().getSelectedSlot();
                    send(mc, new ServerboundSetCarriedItemPacket(selected % 8 + 1));
                    send(mc, new ServerboundSetCarriedItemPacket(selected % 7 + 2));
                    send(mc, new ServerboundSetCarriedItemPacket(selected));
                } else if (mainHand.getValue() && (mc.player.getUseItemRemainingTicks() <= 3 || mc.player.tickCount % 2 == 0)) {
                    send(mc, new ServerboundUseItemPacket(InteractionHand.OFF_HAND, mintSequence(mc),
                            mc.player.getYRot(), mc.player.getXRot()));
                }
            }
            case Matrix2 -> {
                if (mc.player.onGround()) {
                    Vec3 v = mc.player.getDeltaMovement();
                    if (mc.player.tickCount % 2 == 0) {
                        mc.player.setDeltaMovement(v.x * 0.5, v.y, v.z * 0.5);
                    } else {
                        mc.player.setDeltaMovement(v.x * 0.95, v.y, v.z * 0.95);
                    }
                }
            }
            case LFCraft -> {
                if (mc.player.getUseItemRemainingTicks() <= 3) {
                    send(mc, new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                            mc.player.blockPosition().above(), Direction.NORTH, mintSequence(mc)));
                }
            }
            default -> {}
        }
    }

    /**
     * Slowdown-cancel gate that MixinLocalPlayer#noSlow$suppressUsingItem reads. Skip mode is
     * a verbatim port of Phobos's NoSlow.accept() ordinal-7 branch (phobos-crack.jar,
     * org.phobos.secure...NoSlow): cancel the use-item slowdown ONLY on odd use-ticks after
     * the first (getTicksUsingItem() > 1 && getTicksUsingItem() % 2 != 0). Phobos's own
     * slowdown-listener does exactly "if (accept()) event.setCancelled(true)" -- suppressing
     * isUsingItem() in modifyInput() is the same cancel. Netting slowdown on ~half the ticks
     * gives ~50% averaged speed while spreading the gain thin enough that Grim's
     * lag-compensated re-sim never sees a single-tick deviation past its setback threshold
     * ("Abuses lag compensation to gain 50% of walk speed"). Skip runs NO packet trick
     * (Phobos's packet listener never handles ordinal 7). Every other mode keeps ThunderHack's
     * per-item canNoSlow() gate (suppress every tick).
     */
    public boolean shouldCancelSlowdown() {
        if (mode.getValue() == Mode.Skip) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return false;
            return mc.player.getTicksUsingItem() > 1 && mc.player.getTicksUsingItem() % 2 != 0;
        }
        return canNoSlow();
    }

    /**
     * Public gate, mirrors ThunderHack's canNoSlow() -- exposed for other modules to query
     * "is it safe to skip vanilla's use-item slowdown right now", not called internally here
     * (matches the original: onUpdate() never calls it either).
     */
    public boolean canNoSlow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (mode.getValue() == Mode.Matrix3) return false;

        if (!food.getValue() && mc.player.getUseItem().has(DataComponents.FOOD)) return false;
        if (!shield.getValue() && mc.player.getUseItem().getItem() == Items.SHIELD) return false;
        if (!projectiles.getValue() && (mc.player.getUseItem().getItem() == Items.CROSSBOW
                || mc.player.getUseItem().getItem() == Items.BOW || mc.player.getUseItem().getItem() == Items.TRIDENT)) return false;
        if (mode.getValue() == Mode.MusteryGrief && mc.player.onGround() && !mc.options.keyJump.isDown()) return false;
        if (!mainHand.getValue() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return false;

        if ((mc.player.getOffhandItem().has(DataComponents.FOOD) || mc.player.getOffhandItem().getItem() == Items.SHIELD)
                && (mode.getValue() == Mode.GrimNew || mode.getValue() == Mode.Grim)
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND) return false;

        return true;
    }
}
