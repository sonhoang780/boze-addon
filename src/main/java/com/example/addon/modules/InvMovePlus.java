package com.example.addon.modules;

import com.example.addon.mixin.InvMoveLocalPlayerAccessor;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ToggleOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * InvMovePlus — lets you interact with your inventory while walking on strict servers.
 *
 * Always GrimV2: GrimAC v2 flags inventory clicks via MultiActionsC
 * (stableKey "grim.multiactions.inventory_click_while_moving"), which ORs two
 * independent triggers -- BOTH must be defeated, not just one:
 *
 * 1. {@code player.supportsEndTick() && knownInput.moving()} -- the server's
 *    cached copy of the client's last ServerboundPlayerInputPacket. This ONLY
 *    applies when {@code supportsEndTick()} is true, which requires BOTH the
 *    client AND the real backend server's protocol to be >= 1.21.2
 *    (GrimPlayer#supportsEndTick, checked against PacketEvents' ServerManager
 *    version -- the actual server, not our client). On a ViaFabricPlus-bridged
 *    OLDER-protocol server this is permanently false, so spoofing the input
 *    packet alone (the original GrimV2 implementation) does NOTHING there --
 *    confirmed by Replenish still failing while moving on such a server.
 *
 * 2. {@code isVerboseSprinting()} -- {@code player.isSprinting}, a plain STATE
 *    flag Grim flips only from ServerboundPlayerCommandPacket's START_SPRINTING/
 *    STOP_SPRINTING actions (ac.grim.grimac.events.packets.PacketEntityAction).
 *    This packet exists on every protocol version, so it's the trigger that
 *    actually fires against Via-bridged old-protocol servers. Fix: send
 *    STOP_SPRINTING immediately before the click, START_SPRINTING immediately
 *    after (only if the player was actually sprinting). Verified against Grim's
 *    own SprintA-G checks and BadPacketsF (which only flags a REDUNDANT
 *    start/stop, i.e. sending the same state twice in a row) -- a real
 *    state-toggling pair is never flagged by any of them.
 *
 * Both fixes are applied unconditionally per click since neither depends on
 * knowing the real server's protocol version up front.
 */
public class InvMovePlus extends AddonModule {
    public static final InvMovePlus INSTANCE = new InvMovePlus();
    // Set by beforeClick when it sent STOP_SPRINTING for the click currently in flight, so
    // afterClick knows to resume it. Container clicks are never re-entrant on this thread.
    private boolean stoppedSprintForClick = false;

    public InvMovePlus() {
        super("InvMovePlus", "Bypass inventory-while-moving checks on GrimV2/NCP servers.");
    }

    // ── GrimV2: spoof input + toggle sprint around the click, let it through immediately ─

    /**
     * Called from MixinMultiPlayerGameMode at the HEAD of handleContainerInput. Never
     * cancels the original call -- GrimV2 doesn't defer/queue clicks, it just lies to the
     * server right before the click packet goes out.
     */
    public void beforeClick(int containerId, int slotId, int buttonNum, ContainerInput input) {
        stoppedSprintForClick = false;
        if (!getState()) return;
        if (ControlRocket.invMoveBypass) return; // ControlRocket chestplate-swap needs precise ordering
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        spoofStationaryInput(mc);

        if (mc.player.isSprinting()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            stoppedSprintForClick = true;
        }
    }

    /** Called from MixinMultiPlayerGameMode at the TAIL of handleContainerInput. */
    public void afterClick() {
        if (!stoppedSprintForClick) return;
        stoppedSprintForClick = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
    }

    /** Sends one input packet with every movement bit zeroed, only if the real state has any set. */
    private static void spoofStationaryInput(Minecraft mc) {
        LocalPlayer player = mc.player;
        Input real = player.input.keyPresses;
        if (!real.forward() && !real.backward() && !real.left() && !real.right() && !real.jump()) return;
        Input fake = new Input(false, false, false, false, false, real.shift(), real.sprint());
        mc.getConnection().send(new ServerboundPlayerInputPacket(fake));
        ((InvMoveLocalPlayerAccessor) player).invMove$setLastSentInput(fake);
    }
}
