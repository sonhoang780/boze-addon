package com.example.addon.modules;

import com.example.addon.mixin.EntityFlagAccessor;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import net.minecraft.network.chat.Component;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * EBounce+ — "Ground Build" recast mode, ported from lambda-client's BounceElytraFly
 * (github.com/lambda-client/lambda, elytrafly/modes/BounceElytraFly.kt), minus obstacle
 * passing (no PasserSettings/ObstaclePassingMode equivalent here) and Y-motion diagonal
 * sneak-assist (obstacle-adjacent, needs a RotationManager/line-projection stack this
 * codebase doesn't have -- see conversation, skipped by explicit choice).
 *
 * Core mechanism, matching lambda instead of the old fold/pendingFold/recastPhase state
 * machine: HOLD JUMP every tick while isFallFlying (mirrors their
 * `(player.isGliding && jump) || jumpThisTick` in EventInput). Vanilla itself re-sends
 * START_FALL_FLYING and resumes gliding through a brief ground graze as long as jump
 * stays held through the touch -- the old multi-tick cancel→wait→resend cycle was
 * fighting vanilla instead of leaning on it, which is why it visibly "stood up" every
 * bounce instead of diving continuously.
 *
 * Ported:
 * - Continuous jump-hold while gliding + auto-launch from ground (Takeoff/Jump settings).
 * - AutoPitch: force glidePitch down every tick while gliding (this codebase's own
 *   camera-safe rotation via MixinEntity, not lambda's rotationRequest/RotationManager).
 * - MinimizePackets: latch the takeoff resend instead of spamming it every tick.
 * - FlagPause: freeze everything for N ticks after the server sends a
 *   ClientboundPlayerPositionPacket (position-correction / movement-check flag), instead
 *   of continuing to act right when the server is scrutinizing you.
 * - Ground-touch gap bridging (GlideDetect setting, two modes):
 *   VirtualMask (default) ports lambda's `isGliding()` override verbatim in spirit --
 *   isGlidingMasked() below latches "still gliding" for EBounce+'s OWN checks (pitch
 *   override, jump-hold) without touching the real vanilla flag; !interrupting and
 *   !BaritoneHandler.isActive from lambda's version are dropped (no equivalents here).
 *   EntityFlag writes the real FALL_FLYING flag immediately via a mixin accessor
 *   (Entity#setSharedFlag) -- what this module used before VirtualMask was ported, kept
 *   as a fallback since it's more invasive (touches state other systems also read).
 *   Either way this is the fix for the "stands up instead of diving" symptom: whichever
 *   mode is active, isFlyingNow reads true THIS SAME tick, so the pitch override never
 *   reverts to the real camera angle for a visible gap.
 *
 * - FakeLag/queuePackets: buffer outgoing packets during the bounce-touch tick, flush
 *   right after -- ported using the same trick as lambda's PacketUtils.sendPacketSilently
 *   (public, github.com/lambda-client/lambda util/PacketUtils.kt): that helper bypasses
 *   their own send-hook by calling the RAW netty Connection.send(packet, null, true)
 *   instead of the higher-level listener method their mixin actually hooks. Verified the
 *   same layering exists here: Minecraft#getConnection() returns ClientPacketListener
 *   (the high-level listener, where EventPacket.Send is presumably hooked, same as every
 *   fabric mod convention), and ClientPacketListener#getConnection() returns the distinct
 *   raw net.minecraft.network.Connection with its own send(Packet, ChannelFutureListener,
 *   boolean) + isConnected() -- calling that directly skips the listener's send() (and
 *   whatever hooks it) entirely, same as lambda's technique.
 *
 * - Ping-packet defer (incoming half of FakeLag): mirrors lambda's PacketEvent.Receive.Pre
 *   queuing CommonPingS2CPacket (ClientboundPingPacket here) during the bounce-touch tick
 *   and replaying it after via handle() straight to the listener -- bypassing
 *   EventPacket.Receive so it isn't captured a second time, same idea as
 *   sendPacketSilently but for the receive side.
 *
 * - Eligibility gating: ported PlayerUtils.canStartGliding/canTakeoff/
 *   canGlideWithChestPiece verbatim in spirit as canGlideNow() below -- not just
 *   "elytra equipped": also refuses while riding a vehicle, on a climbable, in water,
 *   under Levitation, or in creative/spectator flight, and uses the real vanilla
 *   LivingEntity#canGlideUsing (curse of binding / equipment-slot validity) instead of a
 *   bare item-type check.
 *
 * NOT ported:
 * - Obstacle passing (ObstaclePassingMode/PasserSettings) -- explicitly out of scope.
 * - Y-motion diagonal sneak-assist -- obstacle-adjacent, no rotation-request/line-math
 *   infra here; skipped by explicit choice rather than half-built speculatively.
 */
public class EBouncePlus extends AddonModule {
    public static final EBouncePlus INSTANCE = new EBouncePlus();

    public final ToggleOption takeoff = new ToggleOption(this, "Takeoff",
        "Automatically jumps and initiates gliding when grounded with an elytra equipped.", true);

    public final ToggleOption autoPitch = new ToggleOption(this, "AutoPitch",
        "Automatically pitches the player's rotation down while gliding to bounce at faster speeds.", true);

    public final SliderOption glidePitch = new SliderOption(this, "Pitch",
        "", 90, 0.0, 90.0, 1.0);

    public final ToggleOption minimizePackets = new ToggleOption(this, "MinimizePackets",
        "Shrinks the amount of start fly packets sent to the server as much as possible", true);

    public final SliderOption flagPause = new SliderOption(this, "FlagPause",
        "How long to pause if the server flags you for a movement check", 5, 0, 100, 1);

    public final ToggleOption fakeLag = new ToggleOption(this, "FakeLag",
        "Emulates the player lagging to allow flying in 1x2 tunnels", true);

    public enum GlideDetectMode { VirtualMask, EntityFlag }
    public final ModeOption<GlideDetectMode> glideDetectMode = new ModeOption<>(this, "GlideDetect",
        "How EBounce+ bridges the ground-touch gap. VirtualMask (default, lambda's isGliding() override): only masks EBounce+'s OWN checks -- the real vanilla FALL_FLYING flag still reads false during the gap for physics/other modules, safer (doesn't touch shared entity state) but relies on our own pitch-override/jump-hold decisions being driven by the masked value, not the real flag. EntityFlag: writes the real flag immediately via a mixin accessor -- more invasive, kept as a fallback if VirtualMask tests unstable in-game.", GlideDetectMode.VirtualMask);

    public final ToggleOption putOnElytra = new ToggleOption(this, "PutOnElytra",
        "Safety net, independent of the glide logic above: if the elytra ends up off the chest slot -- unequipped into the inventory, or stuck on the cursor from an interrupted swap (any module's, not just this one) -- immediately equip/place it back. Falls back to an empty inventory slot, or swaps with an arbitrary occupied one if the inventory is full, when the cursor is the one holding it and the chest slot swap alone doesn't clear it.", true);

    public final ToggleOption debug = new ToggleOption(this, "Debug",
        "Print chat lines on every recast trigger, jump-hold fire, flag pause, and unexpected glide-stop so the exact branch causing weird behaviour can be pinned down.", false);

    public static volatile boolean pitchOverrideActive = false;
    public static volatile float   savedCameraPitch    = 0f;

    private boolean doJump = false;
    // Ground-touch was just detected this tick and hasn't been resolved with a
    // START_FALL_FLYING resend yet. Separate from the initial-takeoff latch below --
    // a touch mid-glide and a cold takeoff from a dead stop are different events.
    private boolean pendingResend = false;
    // false = may send the initial takeoff command now; true = already sent, waiting for
    // the glide flag echo (isFallFlying/onGround) before sending again. Gated by
    // minimizePackets -- see its description.
    private boolean takeoffPending = false;
    // Ticks left to freeze everything after a server position-correction packet.
    // Decremented once per tick in onTickPre; onTickPost checks (without decrementing
    // again) the value Pre already updated this same tick.
    private int flagPauseTicksLeft = 0;
    // Purely for --Debug logging (edge detection on isFallFlying); never read by
    // behavior logic.
    private boolean wasFlyingForLog = false;
    // VirtualMask latch (lambda's prevGliding): last masked "are we gliding" verdict.
    // Only read/written by isGlidingMasked() below when glideDetectMode == VirtualMask.
    private boolean prevGliding = false;
    // FakeLag: outgoing packets captured during the bounce-touch tick, flushed via the
    // raw Connection (bypassing whatever hooks ClientPacketListener#send) right after.
    private final Deque<Packet<?>> sendPacketQueue = new ArrayDeque<>();
    // FakeLag, incoming half: ClientboundPingPacket deferred during the bounce-touch tick
    // (mirrors lambda's PacketEvent.Receive.Pre queuing CommonPingS2CPacket), flushed by
    // handing it straight to the listener -- bypassing EventPacket.Receive so it isn't
    // captured a second time, same idea as sendPacketSilently but for the receive side.
    private final Deque<ClientboundPingPacket> pingPacketQueue = new ArrayDeque<>();

    public EBouncePlus() {
        super("EBounce+", "Infinite Durability for recast, Fuck your boze client.");
    }

    @Override
    public void onEnable() {
        doJump = false;
        pendingResend = false;
        takeoffPending = false;
        flagPauseTicksLeft = 0;
        wasFlyingForLog = false;
        prevGliding = false;
        pitchOverrideActive = false;
    }

    @Override
    public void onDisable() {
        doJump = false;
        pendingResend = false;
        takeoffPending = false;
        flagPauseTicksLeft = 0;
        prevGliding = false;
        Minecraft mc = Minecraft.getInstance();
        flushPackets(mc);
        flushPingPackets(mc);
        if (pitchOverrideActive) {
            if (mc.player != null) mc.player.setXRot(savedCameraPitch);
            pitchOverrideActive = false;
        }
    }

    // ── EventPacket.Receive: detect anti-cheat position-correction flags ────

    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (event.packet instanceof ClientboundPingPacket ping) {
            Minecraft mcForPing = Minecraft.getInstance();
            boolean inDip = fakeLag.getValue() && mcForPing.player != null
                && mcForPing.player.isFallFlying() && mcForPing.player.onGround() && canGlideNow(mcForPing);
            if (inDip) {
                pingPacketQueue.add(ping);
                event.cancel();
                return;
            }
            flushPingPackets(mcForPing);
            return;
        }

        if (!(event.packet instanceof ClientboundPlayerPositionPacket)) return;

        flagPauseTicksLeft = flagPause.getValue().intValue();
        // Cancel any in-flight jump/resend so nothing fires during the freeze window
        // that follows -- a queued action landing mid-pause would be exactly the kind
        // of activity the pause is meant to avoid. prevGliding is NOT one of those:
        // it's isGlidingMasked's multi-tick "were we gliding" latch, not a one-shot
        // pending action -- clearing it here was the actual cause of the reported
        // "UNEXPECTED STOP" mid-bounce (onTickPre skips entirely while frozen, so
        // clearing it here is the only place that could break the latch). ANY
        // ClientboundPlayerPositionPacket triggers this pause, not just genuine
        // anti-cheat rubber-bands -- vanilla sends these routinely (e.g. periodic
        // resync) even during a perfectly healthy bounce chain. With prevGliding wiped
        // here, the first isGlidingMasked() call after the freeze ends sees
        // prevGliding=false and falls through to the real (still-momentarily-false)
        // flag instead of latching true, reading as a real stop and killing the whole
        // recast chain right when the freeze that was supposed to protect it ends.
        // Leaving prevGliding untouched lets the latch carry through the freeze
        // exactly like it already carries through any other single-tick flag drop.
        doJump = false;
        pendingResend = false;
        takeoffPending = false;
        Minecraft mc = Minecraft.getInstance();
        flushPackets(mc);
        flushPingPackets(mc);
        if (debug.getValue()) {
            debugLog(mc, "FLAG detected (position-correction packet) -- pausing " + flagPauseTicksLeft + " ticks");
        }
    }

    // ── EventPacket.Send: queue outgoing packets during the bounce-touch tick ─

    @EventHandler
    private void onPacketSend(EventPacket.Send event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean inDip = fakeLag.getValue()
            && mc.player.isFallFlying() && mc.player.onGround() && canGlideNow(mc);
        if (inDip) {
            sendPacketQueue.add(event.packet);
            event.cancel();
            return;
        }
        flushPackets(mc);
    }

    private void flushPackets(Minecraft mc) {
        if (sendPacketQueue.isEmpty()) return;
        Connection connection = mc.getConnection() == null ? null : mc.getConnection().getConnection();
        if (connection == null || !connection.isConnected()) {
            sendPacketQueue.clear();
            return;
        }
        Packet<?> packet;
        while ((packet = sendPacketQueue.poll()) != null) {
            connection.send(packet, null, true);
        }
    }

    // Hands each deferred ping straight to the listener instead of re-sending it through
    // Minecraft#getConnection() -- that would re-fire EventPacket.Receive and queue it
    // right back. This is the receive-side equivalent of flushPackets()'s raw send.
    private void flushPingPackets(Minecraft mc) {
        if (pingPacketQueue.isEmpty()) return;
        if (mc.getConnection() == null) {
            pingPacketQueue.clear();
            return;
        }
        ClientboundPingPacket ping;
        while ((ping = pingPacketQueue.poll()) != null) {
            ping.handle(mc.getConnection());
        }
    }

    // ── EventInput: apply jump ───────────────────────────────────────────────

    @EventHandler
    private void onInput(EventInput event) {
        if (doJump) {
            event.jumping = true;
            doJump = false;
        }
    }

    // ── PutOnElytra: safety net, runs regardless of glide eligibility ────────

    @EventHandler
    private void onPutOnElytraTick(EventTick.Pre event) {
        if (!putOnElytra.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        // Cursor-stuck case: only touch this while no UNRELATED container (chest,
        // furnace, ...) is open -- inventoryMenu's slot numbering (6 = chest armor)
        // only applies when it's actually the active menu.
        if (mc.player.containerMenu == mc.player.inventoryMenu) {
            ItemStack carried = mc.player.inventoryMenu.getCarried();
            if (carried.getItem() == Items.ELYTRA) {
                resolveCarriedElytra(mc);
                return; // one action per tick is enough; re-checked next tick
            }
        }

        // Unequipped case: elytra sitting loose in the inventory (an interrupted swap
        // from any module, or a manual unequip) instead of on the chest slot.
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            int slot = findElytraInInventory(mc);
            if (slot != -1) swapIntoChestSlot(mc, slot);
        }
    }

    /**
     * Cursor already holds the elytra (e.g. left stuck there by an interrupted 3-click
     * swap, from ControlRocket's FakeFly or anywhere else). One click on the chest slot
     * places it there if empty, or swaps it with whatever's currently equipped (armor
     * slots always accept a valid chestplate/elytra via a direct click, regardless of
     * current occupant) -- this alone resolves the vast majority of cases. Falls back to
     * an empty inventory slot, then an arbitrary occupied one (full inventory), only if
     * that single click somehow didn't clear the cursor (e.g. a Curse of Binding item
     * already equipped that can't be picked up by a normal click).
     */
    private void resolveCarriedElytra(Minecraft mc) {
        int syncId = mc.player.inventoryMenu.containerId;
        mc.gameMode.handleContainerInput(syncId, 6, 0, ContainerInput.PICKUP, mc.player);
        if (mc.player.inventoryMenu.getCarried().getItem() != Items.ELYTRA) return;

        int empty = findEmptyInventorySlot(mc);
        if (empty != -1) {
            mc.gameMode.handleContainerInput(syncId, empty, 0, ContainerInput.PICKUP, mc.player);
            return;
        }
        int any = findAnyInventorySlot(mc);
        if (any != -1) {
            mc.gameMode.handleContainerInput(syncId, any, 0, ContainerInput.PICKUP, mc.player);
        }
    }

    /** Atomic 3-click swap: elytra currently at {@code slot} in the inventory ends up
     *  in the chest slot, whatever was equipped ends up at {@code slot}. */
    private void swapIntoChestSlot(Minecraft mc, int slot) {
        int syncId = mc.player.inventoryMenu.containerId;
        mc.gameMode.handleContainerInput(syncId, slot, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(syncId, 6, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(syncId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    private int findElytraInInventory(Minecraft mc) {
        for (int i = 9; i <= 44; i++) {
            if (mc.player.inventoryMenu.getSlot(i).getItem().getItem() == Items.ELYTRA) return i;
        }
        return -1;
    }

    private int findEmptyInventorySlot(Minecraft mc) {
        for (int i = 9; i <= 44; i++) {
            if (mc.player.inventoryMenu.getSlot(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    private int findAnyInventorySlot(Minecraft mc) {
        for (int i = 9; i <= 44; i++) {
            if (!mc.player.inventoryMenu.getSlot(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    // ── EventTick.Post: send packets AFTER vanilla movement packet ───────────

    @EventHandler
    private void onTickPost(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (flagPauseTicksLeft > 0) return; // frozen -- see onPacketReceive

        // A ground touch was detected in Pre this tick -- reassert the glide command so
        // the server also resumes gliding (the local flag was already set immediately in
        // Pre; this is the actual server-side re-affirmation).
        if (pendingResend) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            pendingResend = false;
            if (debug.getValue()) {
                debugLog(mc, "RECAST resolve: START_FALL_FLYING resent (onGround="
                    + mc.player.onGround() + " speed=" + horizontalSpeed(mc) + ")");
            }
        }

        // Initial takeoff: airborne but not gliding yet → ask the server to start fall
        // flying. Post runs after this tick's movement packet, so the server already
        // sees onGround=false and accepts the command. minimizePackets latches this to a
        // single send until the glide flag echoes back -- the unconditional per-tick
        // resend (minimizePackets off) previously broke takeoff on this server.
        if (!mc.player.isFallFlying() && !mc.player.onGround() && canGlideNow(mc)) {
            if (!minimizePackets.getValue() || !takeoffPending) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                takeoffPending = true;
            }
        } else if (mc.player.onGround()) {
            takeoffPending = false;
        }

        // Restore real camera pitch (was set to override in Pre for physics packet).
        if (pitchOverrideActive) {
            mc.player.setXRot(savedCameraPitch);
            pitchOverrideActive = false;
        }
    }

    // ── EventTick.Pre: pitch override + jump-hold + recast detection ────────

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        if (!canGlideNow(mc)) {
            reset();
            flushPackets(mc);
            flushPingPackets(mc);
            return;
        }

        // Frozen after a server flag: do nothing at all this tick (no sprint force, no
        // pitch override, no jump-hold) -- let real client/server state settle before
        // touching anything again.
        if (flagPauseTicksLeft > 0) {
            flagPauseTicksLeft--;
            return;
        }

        // Maintain sprint so every jump is a sprint-jump (+0.2 b/t boost)
        mc.player.setSprinting(true);

        boolean isFlyingNow = glideDetectMode.getValue() == GlideDetectMode.EntityFlag
            ? mc.player.isFallFlying()
            : isGlidingMasked(mc);

        // Set pitch override so player.tick() sends it in movement packet.
        // MixinEntity intercepts getXRot(F) → camera unaffected.
        if (isFlyingNow && autoPitch.getValue()) {
            savedCameraPitch = mc.player.getXRot();
            mc.player.setXRot((float)(double) glidePitch.getValue());
            pitchOverrideActive = true;
        }

        // The core mechanism: hold jump every tick while gliding, unconditionally (matches
        // lambda's `(player.isGliding && jump) || jumpThisTick` with jump always true).
        // This is what lets vanilla itself carry the glide through a bounce touch
        // same-tick instead of us cancelling and manually restarting it across several
        // ticks -- that older cancel/wait/resend cycle is what visibly made the player
        // "stand up" every bounce. No toggle: turning this off is just plain vanilla
        // bounce behaviour (stops at the first touch), never a useful state to test in.
        if (isFlyingNow) {
            doJump = true;
        } else if (mc.player.onGround() && takeoff.getValue()) {
            doJump = true;
            if (debug.getValue()) {
                debugLog(mc, "GROUND-JUMP-HOLD fired (onGround, not flying, idle state)");
            }
        }

        // Debug only: flag any tick where gliding stops without a ground touch this same
        // tick -- a real hard landing on terrain, a server-side rubber-band, or a
        // rejection not otherwise accounted for.
        if (debug.getValue()) {
            if (wasFlyingForLog && !isFlyingNow && !mc.player.onGround()) {
                debugLog(mc, "UNEXPECTED STOP: isFallFlying true->false with no ground touch"
                    + " (hCollision=" + mc.player.horizontalCollision
                    + " vy=" + mc.player.getDeltaMovement().y
                    + " speed=" + horizontalSpeed(mc) + ")");
            }
            wasFlyingForLog = isFlyingNow;
        }

        if (!isFlyingNow) return;
        takeoffPending = false;

        // Ground-touch = the bounce: the client's own onGround flag flickers true for
        // one tick at the low point of each bounce, even while still isFallFlying -- this
        // is vanilla's own collision resolution, the SAME authority the server uses to
        // decide ground contact, more reliable than any vy threshold or raycast guess.
        // EntityFlag mode: write the real flag immediately so isFallFlying() reads true
        // this same tick. VirtualMask mode: nothing to write -- isGlidingMasked()'s latch
        // (prevGliding was already true) already made isFlyingNow read true above, which
        // is what keeps the pitch override/jump-hold from lapsing; the real flag is left
        // alone. Either way the server-side resend still happens in Post.
        if (mc.player.onGround()) {
            if (glideDetectMode.getValue() == GlideDetectMode.EntityFlag) {
                ((EntityFlagAccessor) (Object) mc.player).invokeSetSharedFlag(EntityFlagAccessor.getFlagFallFlying(), true);
            }
            pendingResend = true;
            if (debug.getValue()) {
                debugLog(mc, "RECAST TRIGGER (onGround flicker, speed=" + horizontalSpeed(mc) + ")");
            }
        }
    }

    private void debugLog(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[EBounce+] " + msg));
        }
    }

    // Ported from lambda's PlayerUtils.canStartGliding/canTakeoff/canGlideWithChestPiece:
    // not just "elytra equipped" -- also refuses while riding, on a climbable, in water,
    // under Levitation, or in creative/spectator flight, and checks real vanilla
    // curse-of-binding/slot eligibility instead of a bare item-type comparison.
    private static boolean canGlideNow(Minecraft mc) {
        var player = mc.player;
        if (player.getAbilities().flying) return false;
        if (player.isPassenger()) return false;
        if (player.onClimbable()) return false;
        if (player.isInWater()) return false;
        if (player.hasEffect(MobEffects.LEVITATION)) return false;
        return LivingEntity.canGlideUsing(player.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    // Ported from lambda's BounceElytraFly#isGliding() override: if we were gliding last
    // tick and we're not currently frozen by FlagPause, KEEP reporting gliding regardless
    // of the real flag -- masks a transient drop for EBounce+'s own decisions (pitch
    // override, jump-hold, touch detection) without touching the real vanilla flag. Lambda
    // also gates on !interrupting and !BaritoneHandler.isActive; neither has an equivalent
    // here (no obstacle-passing interrupt, no Baritone integration), so those are dropped.
    private boolean isGlidingMasked(Minecraft mc) {
        boolean real = mc.player.isFallFlying();
        if (prevGliding && flagPauseTicksLeft == 0) return true; // masked -- prevGliding untouched
        prevGliding = real;
        return real;
    }

    private static double horizontalSpeed(Minecraft mc) {
        var v = mc.player.getDeltaMovement();
        return Math.sqrt(v.x * v.x + v.z * v.z) * 20.0; // b/t -> b/s
    }

    private void reset() {
        doJump = false;
        pendingResend = false;
        takeoffPending = false;
        flagPauseTicksLeft = 0;
        prevGliding = false;
    }

}
