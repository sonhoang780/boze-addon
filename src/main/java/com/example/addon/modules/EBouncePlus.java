package com.example.addon.modules;

import com.example.addon.mixin.EntityFlagAccessor;
import com.example.addon.util.EarlyTickHooks;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * EBounce+ — "Ground Build" recast mode, ported from lambda-client's BounceElytraFly
 * (github.com/lambda-client/lambda, elytrafly/modes/BounceElytraFly.kt), plus obstacle
 * passing (ObstaclePassingMode/PasserSettings, via Baritone reflection -- see
 * handlePassingObstacles()), minus Y-motion diagonal sneak-assist (obstacle-adjacent,
 * needs a RotationManager/line-projection stack this codebase doesn't have -- skipped by
 * explicit choice).
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
 * - Ground-touch gap bridging: ports lambda's `isGliding()` override verbatim in spirit --
 *   isGlidingMasked() below latches "still gliding" so EBounce+'s OWN checks (pitch
 *   override, jump-hold, recast trigger) read true through a transient real-flag drop;
 *   !interrupting and !BaritoneHandler.isActive from lambda's version are dropped (no
 *   equivalents here). The recast trigger in onTickPre ALWAYS writes the real FALL_FLYING
 *   flag via a mixin accessor (Entity#setSharedFlag) when the raw flag reads false while
 *   masked-flying -- matches lambda's ElytraFlyMode#startFly() (`player.setFlag(...,
 *   true)` unconditionally on every recast). An earlier version of this module had a
 *   VirtualMask/EntityFlag mode toggle (mask-only vs always-write) -- that distinction
 *   doesn't exist in lambda at all (masking decides WHEN to recast, writing the flag is
 *   not optional), so the toggle was removed; the module always does both now.
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

    // Ported from lambda's Blink module (movement/Blink.kt): freezes EVERY outgoing packet
    // (and the deferred ping queue) unconditionally for a fixed number of ticks, no
    // Y-position/isFlyingNow gating at all -- unlike FakeLag/queuePackets above, which only
    // buffers while inside the position-based dip window. Per a Boze dev's own hint on their
    // recast bypass ("blink around when you touch the ground"): trigger a fixed-duration
    // freeze right at the RECAST TRIGGER instant instead of/in addition to the Y-window.
    public final ToggleOption oldBypass = new ToggleOption(this, "OldBypass",
        "Blink-style fixed-duration packet freeze triggered right at the recast touch instant, instead of FakeLag's Y-position dip window.", false);

    public final SliderOption oldBypassDelay = new SliderOption(this, "OldBypassDelay",
        "Ticks to freeze all outgoing packets for after a recast trigger.", 4, 1, 20, 1);

    // Ported from lambda's ObstaclePassingMode.kt/PasserSettings -- raycasts ahead along the
    // flight line for obstructions and paths around them via Baritone (reflection, this addon
    // has no compile-time Baritone dependency). Hidden entirely when Baritone isn't installed
    // (visibility supplier), not just disabled -- there's nothing useful to configure without it.
    public final ToggleOption passObstacles = new ToggleOption(this, "PassObstacles",
        "Automatically paths around obstacles in the flight line using Baritone.", false, EBouncePlus::isBaritoneAvailable);

    public final SliderOption obstacleLookAhead = new SliderOption(this, "ObstacleLookAhead",
        "Blocks to look ahead for obstacles / step by when pathing around one.", 8, 0, 50, 1, EBouncePlus::isBaritoneAvailable);

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

    // OldBypass: ticks left in a Blink-style unconditional packet freeze, started at the
    // instant of a RECAST TRIGGER. Decremented once per tick in onTickPre, independent of
    // flagPauseTicksLeft.
    private int oldBypassFreezeTicksLeft = 0;
    // Purely for --Debug logging (edge detection on isFallFlying); never read by
    // behavior logic.
    private boolean wasFlyingForLog = false;
    // Lambda's own prevGliding latch: last masked "are we gliding" verdict. Read/written by
    // isGlidingMasked() below, always active now (no mode toggle).
    private boolean prevGliding = false;
    // Step 2 of EBOUNCE_LAMBDA_PORT_PLAN.md: lambda's FakeLag/queuePackets dip-window trigger
    // is `player.y - startPos.y < 0.163`, NOT onGround() -- startPos is captured ONCE at
    // module enable (ObstaclePassingMode's `onEnable { startPos = player.pos }`), a fixed
    // elevation reference, not reset per-bounce. Lets the dip window fire without waiting
    // for the (sometimes-late/flickery) onGround flag itself.
    private double startY = 0.0;
    // FakeLag: outgoing packets captured during the bounce-touch tick, flushed via the
    // raw Connection (bypassing whatever hooks ClientPacketListener#send) right after.
    private final Deque<Packet<?>> sendPacketQueue = new ArrayDeque<>();
    // FakeLag, incoming half: ClientboundPingPacket deferred during the bounce-touch tick
    // (mirrors lambda's PacketEvent.Receive.Pre queuing CommonPingS2CPacket), flushed by
    // handing it straight to the listener -- bypassing EventPacket.Receive so it isn't
    // captured a second time, same idea as sendPacketSilently but for the receive side.
    private final Deque<ClientboundPingPacket> pingPacketQueue = new ArrayDeque<>();

    // lambda's Speedometer HUD module (real position-delta-per-tick speed, sampled in
    // TickEvent.Post, alwaysListen -- NOT raw getDeltaMovement()). ObstaclePassingMode's
    // notProgressing check uses Speedometer.calculateSpeed(true, BlocksPerSecond), which is
    // actual displacement -- immune to the touch-ground/recast velocity dips that make raw
    // velocity read near-zero for a tick or two even while genuinely still moving forward.
    // Using raw velocity there (an earlier version of this port) caused false
    // notProgressing during a perfectly clear bounce chain, spamming Baritone goal-sets with
    // no obstacle in front (reported in-game).
    private Vec3 speedPrevPos = Vec3.ZERO;
    private Vec3 speedCurrPos = Vec3.ZERO;

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
        prevJumpKeyDown = false;
        Minecraft mcEnable = Minecraft.getInstance();
        startY = mcEnable.player != null ? mcEnable.player.getY() : 0.0;
        obstacleStartPos = mcEnable.player != null ? mcEnable.player.position() : Vec3.ZERO;
        obstaclePassingToPos = null;
        speedPrevPos = obstacleStartPos;
        speedCurrPos = obstacleStartPos;
        // Step 1 re-enabled, re-ported to match lambda exactly: jump-key edge-detect (not
        // "every tick while grounded") + real Player#startFallFlying() (not raw
        // EntityFlagAccessor reflection write). See earlyTickForceTakeoff() for the writeup
        // and EBOUNCE_LAMBDA_PORT_PLAN.md for the first attempt's root-cause finding.
        EarlyTickHooks.register(earlyTickForceTakeoffRef);
    }

    @Override
    public void onDisable() {
        doJump = false;
        pendingResend = false;
        takeoffPending = false;
        flagPauseTicksLeft = 0;
        oldBypassFreezeTicksLeft = 0;
        prevGliding = false;
        EarlyTickHooks.unregister(earlyTickForceTakeoffRef);
        if (obstaclePassingToPos != null) cancelObstaclePath();
        obstaclePassingToPos = null;
        Minecraft mc = Minecraft.getInstance();
        flushPackets(mc);
        if (pitchOverrideActive) {
            if (mc.player != null) mc.player.setXRot(savedCameraPitch);
            pitchOverrideActive = false;
        }
    }

    // Step 1 of EBOUNCE_LAMBDA_PORT_PLAN.md: force the FALL_FLYING flag + send
    // START_FALL_FLYING while still onGround, from MixinLocalPlayer's tick-HEAD hook
    // (runs before vanilla's own physics tick this frame) instead of Boze's
    // EventTick.Pre -- testing lambda's GlideHandler.onGlide() hypothesis that the takeoff
    // force-flag needs to land strictly before vanilla resolves onGround/gliding state for
    // the frame. A stable Runnable instance (not a lambda literal) so register/unregister
    // in onEnable/onDisable target the exact same object.
    private final Runnable earlyTickForceTakeoffRef = this::earlyTickForceTakeoff;
    // Own tracked copy of last tick's real jump-key state, for the edge-detect above.
    private boolean prevJumpKeyDown = false;

    private void earlyTickForceTakeoff() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        // lambda's GlideHandler edge-detects the REAL jump key (postJump && !preJump) --
        // does NOT fire every tick while grounded+eligible like this method's first version
        // did. Read the raw key every tick (own tracked field, no dependency on where
        // EventInput fires relative to this hook) so a HELD jump key doesn't re-fire.
        boolean jumpKeyDown = mc.options.keyJump.isDown();
        boolean jumpPressedEdge = jumpKeyDown && !prevJumpKeyDown;
        prevJumpKeyDown = jumpKeyDown;

        if (!takeoff.getValue()) return;
        if (flagPauseTicksLeft > 0) return;
        if (mc.player.isFallFlying()) return;
        if (!jumpPressedEdge) return;
        if (!mc.player.onGround()) return;
        if (!canGlideNow(mc)) return;

        // Real vanilla method (Player#startFallFlying, Mojmap name for lambda's Yarn
        // player.startGliding()) instead of the raw EntityFlagAccessor reflection write the
        // first version of this method used -- goes through whatever vanilla itself does
        // when starting a glide, instead of just poking the shared flag directly.
        mc.player.startFallFlying();

        // GlideHandler.onGlide() sends this via `connection.sendPacket(...)` -- the REGULAR
        // hooked pipeline (ClientPlayNetworkHandler.sendPacket = connection.send(block())),
        // NOT sendPacketSilently. Lambda does not special-case the takeoff packet around its
        // own FakeLag -- it can get queued/flushed by queuePackets just like anything else.
        // An earlier version of this method routed it through the raw bypass to dodge that,
        // which was a divergence from lambda, not a fix -- reverted back to the normal path.
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        takeoffPending = true;
        if (debug.getValue()) {
            debugLog(mc, "EARLY-TICK FORCE-TAKEOFF fired (jump-edge, pre-physics onGround)");
        }
    }

    // ── EventPacket.Receive: detect anti-cheat position-correction flags ────

    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (event.packet instanceof ClientboundPingPacket ping) {
            Minecraft mcForPing = Minecraft.getInstance();
            boolean oldBypassFreeze = oldBypass.getValue() && oldBypassFreezeTicksLeft > 0;
            boolean inDip = oldBypassFreeze || (fakeLag.getValue() && mcForPing.player != null
                && isFlyingNow(mcForPing) && isInDipWindow(mcForPing));
            if (inDip) {
                pingPacketQueue.add(ping);
                event.cancel();
                return;
            }
            // No flush here, matching lambda's Receive.Pre exactly -- it has no else-branch
            // at all. Both queues only ever get flushed from the outgoing-packet side
            // (onPacketSend's else-branch below), same single trigger as lambda's
            // Send.Pre-only flushPackets() call.
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
        if (debug.getValue()) {
            debugLog(mc, "FLAG detected (position-correction packet) -- pausing " + flagPauseTicksLeft + " ticks");
        }

        // walkWhenFlagged (hardcoded true, lambda's onFlag hook): proactively walk a bit
        // forward via Baritone right when we get flagged, same as lambda -- deferred to the
        // main thread (mc.execute, matches lambda's runGameScheduled) since this event can
        // fire off the render thread (e.g. Netty IO) and Baritone's API isn't meant to be
        // driven from there.
        if (passObstacles.getValue() && isBaritoneAvailable() && !PathFinder.INSTANCE.getState()
                && mc.player != null) {
            // Snapshot dir/line NOW (matches lambda: getSnappedDir()/findClosestPointOnLine()
            // run synchronously inside onFlag, only the Baritone goal-set call is deferred via
            // runGameScheduled). Deferring the snapshot itself (previous version) let the
            // player move an extra tick before the direction/line were computed.
            Vec3 snappedDir = getSnappedObstacleDir(mc);
            Vec3 closestLinePoint = findClosestPointOnObstacleLine(mc.player.position(), snappedDir);
            mc.execute(() -> {
                if (mc.player == null) return;
                Vec3 pathToPoint = closestLinePoint.add(snappedDir.scale(obstacleLookAhead.getValue().doubleValue()));
                pathToValidObstaclePoint(mc, pathToPoint, snappedDir, false);
            });
        }
    }

    // ── EventPacket.Send: queue outgoing packets during the bounce-touch tick ─

    @EventHandler
    private void onPacketSend(EventPacket.Send event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // OldBypass: unconditional Blink-style freeze, no isFlyingNow/dip-window gating at
        // all -- matches lambda's Blink module exactly (queues everything while active).
        boolean oldBypassFreeze = oldBypass.getValue() && oldBypassFreezeTicksLeft > 0;
        boolean inDip = oldBypassFreeze || (fakeLag.getValue()
            && isFlyingNow(mc) && isInDipWindow(mc));
        if (inDip) {
            sendPacketQueue.add(event.packet);
            event.cancel();
            return;
        }
        flushPackets(mc);
    }

    // Combined flush, matching lambda's single flushPackets() (drains sendPacketQueue AND
    // pingPackets together) -- lambda's Receive.Pre handler never flushes on its own, only
    // Send.Pre's else-branch does, for BOTH queues at once. Kept as one function (not two
    // independently-triggered ones) so ping flushing follows the exact same trigger lambda
    // uses instead of an extra independent trigger on every non-dip incoming ping.
    private void flushPackets(Minecraft mc) {
        if (!sendPacketQueue.isEmpty()) {
            Connection connection = mc.getConnection() == null ? null : mc.getConnection().getConnection();
            if (connection == null || !connection.isConnected()) {
                sendPacketQueue.clear();
            } else {
                Packet<?> packet;
                while ((packet = sendPacketQueue.poll()) != null) {
                    connection.send(packet, null, true);
                }
            }
        }

        if (!pingPacketQueue.isEmpty()) {
            if (mc.getConnection() == null) {
                pingPacketQueue.clear();
            } else {
                // Hands each deferred ping straight to the listener instead of re-sending it
                // through Minecraft#getConnection() -- that would re-fire EventPacket.Receive
                // and queue it right back.
                ClientboundPingPacket ping;
                while ((ping = pingPacketQueue.poll()) != null) {
                    ping.handle(mc.getConnection());
                }
            }
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

        // Speedometer sample -- lambda's TickEvent.Post alwaysListen block, runs regardless
        // of the freeze/pause state below (a real HUD module ticking independently).
        speedPrevPos = speedCurrPos;
        speedCurrPos = mc.player.position();

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
            return;
        }

        if (oldBypassFreezeTicksLeft > 0) oldBypassFreezeTicksLeft--;

        // Obstacle passing takes over entirely this tick if it engages (matches lambda's
        // `if (handlePassingObstacles()) return@listen`, called before the flagPause check
        // so it still runs during a freeze). Clear doJump so a stale jump-hold from a
        // previous tick doesn't fire while Baritone is trying to walk normally.
        if (handlePassingObstacles(mc)) {
            doJump = false;
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

        boolean isFlyingNow = isFlyingNow(mc);

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

        // Recast trigger, matching lambda's ElytraFlyMode.flyOrFakeFly() call site verbatim:
        // `if (minimizePackets && rawFlagTrue && !fakeFly && !yMotion) return; flyOrFakeFly()`
        // -- NOT gated on onGround() at all. The real touch-instant and the raw-flag-false
        // window can land on slightly different ticks (timing isn't perfect); gating on
        // onGround() specifically meant a same-tick miss between the two left the recast
        // waiting for a LATER onGround() read that might not come until the player has
        // genuinely lost all momentum -- observed as a hard "stand up" instead of a seamless
        // bounce-through. Checking raw-flag-false directly catches the gap the instant it
        // opens, regardless of what onGround() reads that same tick.
        // Also always writes the real flag on recast (lambda's startFly() does
        // `player.setFlag(GLIDING_FLAG_INDEX, true)` unconditionally, every time) --
        // masking (isGlidingMasked) is only for deciding WHEN a recast is needed, never a
        // substitute for writing the real flag. The old VirtualMask/EntityFlag mode choice
        // was this addon's own invention, not something lambda actually has; removed.
        boolean rawFlying = mc.player.isFallFlying();
        if (!(minimizePackets.getValue() && rawFlying)) {
            ((EntityFlagAccessor) (Object) mc.player).invokeSetSharedFlag(EntityFlagAccessor.getFlagFallFlying(), true);
            pendingResend = true;
            if (oldBypass.getValue()) {
                oldBypassFreezeTicksLeft = oldBypassDelay.getValue().intValue();
            }
            if (debug.getValue()) {
                debugLog(mc, "RECAST TRIGGER (raw flag false while masked-flying, speed=" + horizontalSpeed(mc) + ")");
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
    // of the real flag -- decides WHEN we're still in a touch-and-continue window (pitch
    // override, jump-hold, recast trigger), it does NOT replace writing the real flag on
    // recast (onTickPre always writes it, matching lambda's startFly()). Lambda also gates
    // on !interrupting (no equivalent -- no obstacle-passing here) and
    // !BaritoneHandler.isActive (ported below as !isBaritoneElytraActive()): if PathFinder's
    // Baritone #elytra process is genuinely driving the path right now, don't mask -- let
    // isFlyingNow track the real flag so this module's pitch-override/jump-hold don't fight
    // Baritone's own landing-disable sequence (MixinChatComponentLandingDetect).
    private boolean isGlidingMasked(Minecraft mc) {
        boolean real = mc.player.isFallFlying();
        if (prevGliding && flagPauseTicksLeft == 0 && !isBaritoneElytraActive()) return true; // masked -- prevGliding untouched
        prevGliding = real;
        return real;
    }

    /**
     * baritone.api.BaritoneAPI#getProvider() -> getPrimaryBaritone(). Shared lookup for
     * every Baritone reflection call in this class. Fails closed (null) if baritone isn't
     * loaded at all.
     */
    private static Object getPrimaryBaritone() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isBaritoneAvailable() {
        return getPrimaryBaritone() != null;
    }

    /**
     * -> IBaritone#getElytraProcess() -> IBaritoneProcess#isActive(). Same reflection
     * pattern as PathFinder.java's getElytraProcess()/isProcessActive() (kept local here
     * rather than exposing PathFinder's private internals publicly for one caller). Fails
     * closed: any missing class/method (baritone not loaded, PathFinder not running
     * #elytra) reads as "not active".
     */
    private static boolean isBaritoneElytraActive() {
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return false;
            Object elytraProcess = baritone.getClass().getMethod("getElytraProcess").invoke(baritone);
            if (elytraProcess == null) return false;
            return (boolean) elytraProcess.getClass().getMethod("isActive").invoke(elytraProcess);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Obstacle passing (ported from lambda's ObstaclePassingMode.kt) ──────

    // Flight-line reference, captured once at enable -- lambda's `onEnable { startPos =
    // player.pos }`.
    private Vec3 obstacleStartPos = Vec3.ZERO;
    // Non-null while Baritone is actively pathing around a detected obstacle.
    private Vec3 obstaclePassingToPos = null;

    // Hardcoded lambda PasserSettings defaults not exposed as options (user chose to trim
    // the option surface to just PassObstacles + ObstacleLookAhead):
    private static final double OBSTACLE_MIN_HEIGHT = 0.063;
    private static final boolean OBSTACLE_HEAD_HITTERS = true;
    private static final double OBSTACLE_ACCEPTABLE_OFFSET = 2.0;
    private static final double OBSTACLE_DIRECTION_STEP = 45.0;
    /**
     * Direction from obstacleStartPos to the player's current position, snapped to the
     * nearest OBSTACLE_DIRECTION_STEP degrees -- lambda's getSnappedDir()/lockYawToStep().
     * Corrected to match verbatim: lambda normalizes the FULL 3D travel vector first
     * (`player.pos.subtract(startPos).normalize()`), THEN zeroes y -- a steep dive "spends"
     * some of that unit length on the vertical component, so the resulting horizontal
     * vector's magnitude shrinks the steeper the dive. An earlier version of this method
     * normalized by horizontal-length-only, which always produced a unit-horizontal vector
     * regardless of dive angle -- same snapped ANGLE (atan2 is scale-invariant for a
     * uniformly-scaled x/z pair) but a different final magnitude, which matters since it's
     * later used as a step size (dir.multiply(lookAhead) etc). y is 0 in the result either
     * way, matching lambda's Vec3d(it.x, 0.0, it.z) fed into lockYawToStep.
     */
    private Vec3 getSnappedObstacleDir(Minecraft mc) {
        Vec3 travelDiff = mc.player.position().subtract(obstacleStartPos);
        Vec3 travelNorm = travelDiff.lengthSqr() > 1.0E-12 ? travelDiff.normalize() : new Vec3(0, 0, 1);
        double x = travelNorm.x;
        double z = travelNorm.z;

        double yawDeg = Math.toDegrees(Math.atan2(z, x));
        double normalizedYaw = ((yawDeg % 360.0) + 360.0) % 360.0;
        double steps = normalizedYaw / OBSTACLE_DIRECTION_STEP;
        double lockedYawDeg = Math.round(steps) * OBSTACLE_DIRECTION_STEP;
        double normalizedLockedYawDeg = ((lockedYawDeg % 360.0) + 360.0) % 360.0;
        double lockedYaw = Math.toRadians(normalizedLockedYawDeg);

        double horizontalLength = Math.sqrt(x * x + z * z);
        return new Vec3(Math.cos(lockedYaw) * horizontalLength, 0.0, Math.sin(lockedYaw) * horizontalLength);
    }

    /** lambda's Vec3d.findClosestPointOnLine(): projects pos onto the line through
     *  obstacleStartPos along snappedDir. */
    private Vec3 findClosestPointOnObstacleLine(Vec3 pos, Vec3 snappedDir) {
        Vec3 startToCurrent = pos.subtract(obstacleStartPos);
        double denom = snappedDir.dot(snappedDir);
        double t = denom > 1.0E-9 ? startToCurrent.dot(snappedDir) / denom : 0.0;
        return obstacleStartPos.add(snappedDir.scale(t));
    }

    /** lambda's Vec3d.rayCastObstructed(): a single block-collider raycast up to
     *  ObstacleLookAhead blocks along dir. */
    private boolean obstacleRayCastObstructed(Minecraft mc, Vec3 from, Vec3 dir) {
        double lookAhead = obstacleLookAhead.getValue().doubleValue();
        if (lookAhead <= 0) return false;
        Vec3 dirNorm = dir.length() > 1.0E-6 ? dir.normalize() : new Vec3(0, 0, 1);
        Vec3 to = from.add(dirNorm.scale(lookAhead));
        BlockHitResult hit = mc.level.clip(new ClipContext(
            from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    /** lambda's Vec3d.isObstructed(): block below must be solid (else it's a drop, not an
     *  obstacle), plus raycasts at OBSTACLE_MIN_HEIGHT and (if headHitters) at 1.01/1.99. */
    private boolean isObstacleObstructed(Minecraft mc, Vec3 pos, Vec3 dir) {
        BlockPos below = BlockPos.containing(pos.x, pos.y, pos.z).below();
        boolean groundSolid = mc.level.getBlockState(below).isFaceSturdy(mc.level, below, net.minecraft.core.Direction.UP);
        if (!groundSolid) return true;

        if (obstacleRayCastObstructed(mc, pos.add(0, OBSTACLE_MIN_HEIGHT, 0), dir)) return true;
        if (OBSTACLE_HEAD_HITTERS) {
            if (obstacleRayCastObstructed(mc, pos.add(0, 1.01, 0), dir)) return true;
            if (obstacleRayCastObstructed(mc, pos.add(0, 1.99, 0), dir)) return true;
        }
        return false;
    }

    /** lambda's pathToValidPoint(): steps along dir by ObstacleLookAhead until an
     *  unobstructed point is found, then hands it to Baritone. */
    private void pathToValidObstaclePoint(Minecraft mc, Vec3 startSearchPos, Vec3 dir, boolean initialBlockedCheck) {
        double lookAhead = Math.max(1.0, obstacleLookAhead.getValue().doubleValue());
        Vec3 dirNorm = dir.length() > 1.0E-6 ? dir.normalize() : new Vec3(0, 0, 1);
        boolean skippingFirstCheck = !initialBlockedCheck;
        Vec3 searchPos = startSearchPos;
        int guard = 0;
        while ((skippingFirstCheck || isObstacleObstructed(mc, searchPos, dirNorm)) && guard++ < 200) {
            searchPos = searchPos.add(dirNorm.scale(lookAhead));
            skippingFirstCheck = false;
        }
        passObstacleTo(searchPos);
    }

    /** baritone.api.pathing.goals.GoalGetToBlock(BlockPos) -> IBaritone#getCustomGoalProcess()
     *  -> ICustomGoalProcess#setGoalAndPath(Goal). Verified against the real loaded jar
     *  (mods/26.1/baritone-1.17.0+26.1.2.jar) via javap -- IPathingBehavior has NO
     *  setGoalAndPath at all (an earlier version of this method called it there and silently
     *  no-op'd, caught by the catch-all below with zero visibility into the failure). */
    private void passObstacleTo(Vec3 pos) {
        obstaclePassingToPos = pos;
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return;
            BlockPos blockPos = BlockPos.containing(pos.x, pos.y, pos.z);
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Object goal = Class.forName("baritone.api.pathing.goals.GoalGetToBlock")
                .getConstructor(BlockPos.class).newInstance(blockPos);
            Object customGoalProcess = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
            customGoalProcess.getClass().getMethod("setGoalAndPath", goalClass).invoke(customGoalProcess, goal);
            if (debug.getValue()) {
                debugLog(Minecraft.getInstance(), "OBSTACLE PASS: goal set to " + blockPos);
            }
        } catch (Throwable t) {
            if (debug.getValue()) {
                debugLog(Minecraft.getInstance(), "OBSTACLE PASS FAILED: " + t);
            }
        }
    }

    /** IBaritone#getPathingBehavior() -> IPathingBehavior#cancelEverything(). */
    private static void cancelObstaclePath() {
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) return;
            Object pathingBehavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
        } catch (Throwable ignored) {}
    }

    /**
     * lambda's ObstaclePassingMode#handlePassingObstacles(), verbatim in spirit. Returns
     * true if obstacle-passing took over this tick (caller must short-circuit). Defers
     * entirely to PathFinder if it's active (this addon's own separate Baritone-#elytra
     * module) to avoid two systems fighting over the same Baritone goal.
     */
    private boolean handlePassingObstacles(Minecraft mc) {
        if (PathFinder.INSTANCE.getState()) {
            obstaclePassingToPos = null;
            return false;
        }
        if (!passObstacles.getValue()) return false;
        if (!isBaritoneAvailable()) return false;

        if (!isBaritoneElytraActive()) obstaclePassingToPos = null;

        Vec3 playerPos = mc.player.position();
        double distFromStart = Math.sqrt(
            Math.pow(playerPos.x - obstacleStartPos.x, 2) + Math.pow(playerPos.z - obstacleStartPos.z, 2));
        if (distFromStart <= 0.1) return false;

        Vec3 snappedDir = getSnappedObstacleDir(mc);
        Vec3 closestLinePoint = findClosestPointOnObstacleLine(playerPos, snappedDir);

        if (obstaclePassingToPos != null) {
            if (isObstacleObstructed(mc, obstaclePassingToPos, snappedDir)) {
                pathToValidObstaclePoint(mc, obstaclePassingToPos, snappedDir, false);
            }
            return true;
        }

        if (!mc.player.onGround()) return false;

        boolean notProgressing = horizontalSpeedometer() < 0.01;
        if (isFlyingNow(mc) && notProgressing) {
            pathToValidObstaclePoint(mc, closestLinePoint, snappedDir, false);
            return true;
        }

        Vec3 xy = new Vec3(playerPos.x, closestLinePoint.y, playerPos.z);
        double distanceToLine = xy.distanceTo(closestLinePoint) + Math.min(0.0, playerPos.y - closestLinePoint.y);
        if (distanceToLine > OBSTACLE_ACCEPTABLE_OFFSET) {
            pathToValidObstaclePoint(mc, closestLinePoint, snappedDir, true);
            return true;
        }

        if (isObstacleObstructed(mc, xy, snappedDir)) {
            pathToValidObstaclePoint(mc, closestLinePoint, snappedDir, false);
            return true;
        }

        return isBaritoneElytraActive();
    }

    // Shared "are we gliding" resolution used by both onTickPre and the FakeLag dip-window
    // checks below -- always the masked value now, matching lambda's `player.isGliding`
    // (always routed through its isGliding() override, no raw-only mode exists in lambda).
    private boolean isFlyingNow(Minecraft mc) {
        return isGlidingMasked(mc);
    }

    // Step 2: lambda's queuePackets dip-window check, verbatim -- `player.y - startPos.y <
    // 0.163` where startPos is the ONE-TIME enable-time position (see startY field above).
    private boolean isInDipWindow(Minecraft mc) {
        return mc.player.getY() - startY < 0.163;
    }

    private static double horizontalSpeed(Minecraft mc) {
        var v = mc.player.getDeltaMovement();
        return Math.sqrt(v.x * v.x + v.z * v.z) * 20.0; // b/t -> b/s
    }

    /** lambda's Speedometer.calculateSpeed(true, BlocksPerSecond): actual horizontal
     *  position delta over the last tick, not raw velocity -- see speedPrevPos/speedCurrPos
     *  comment above for why this matters for notProgressing specifically. */
    private double horizontalSpeedometer() {
        Vec3 delta = speedCurrPos.subtract(speedPrevPos);
        return Math.sqrt(delta.x * delta.x + delta.z * delta.z) * 20.0;
    }

    private void reset() {
        doJump = false;
        pendingResend = false;
        takeoffPending = false;
        flagPauseTicksLeft = 0;
        oldBypassFreezeTicksLeft = 0;
        prevGliding = false;
    }

}
