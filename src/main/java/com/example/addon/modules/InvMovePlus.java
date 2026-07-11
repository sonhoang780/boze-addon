package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * InvMovePlus — lets you interact with your inventory while walking on strict servers.
 *
 * NCP mode   : no-op (NCP doesn't block inv-while-moving the way Grim does).
 * GrimStrict : intercepts every container click while the player is moving,
 *              zeros movement input for 1 tick so the server sees the player stop,
 *              then replays the queued click(s).  This prevents the
 *              "InventoryOpen while moving" flag that GrimAC raises.
 *
 * Interception happens at MultiPlayerGameMode.handleContainerInput (via
 * MixinMultiPlayerGameMode), NOT at the outgoing-packet level. A previous version
 * cancelled ServerboundContainerClickPackets and re-sent the raw packet objects 2
 * ticks later via connection.send() — on 1.21.5+ those packets carry hashed item
 * stacks, and re-sending them directly bypasses ViaFabricPlus's full-stack capture
 * (done inside handleContainerInput), producing "The CONTAINER_CLICK packet could
 * not be remapped without breaking content!" errors and dropped clicks on
 * older-protocol servers. Deferring the whole handleContainerInput call instead
 * means the local inventory mutation, the packet build, and Via's capture all
 * happen together at flush time — exactly the code path ViaVersion recommends.
 *
 * Tick flow (GrimStrict):
 *   Tick N  — handleContainerInput fires: click queued + cancelled, frozenTicks=2
 *             EventTick.Post: frozenTicks 2→1 (nothing replayed yet)
 *   Tick N+1 — EventInput fires: frozenTicks=1 → forward/backward/left/right/jumping zeroed
 *              player physics runs with 0 input → sends 0-velocity move packet
 *              EventTick.Post: frozenTicks 1→0 → pending clicks replayed
 *   Grim receives: [tick-N move packet | tick-N+1 move packet with 0 velocity | container click]
 */
public class InvMovePlus extends AddonModule {
    public static final InvMovePlus INSTANCE = new InvMovePlus();

    public enum Mode { NCP, GrimStrict }

    public final ModeOption<Mode> mode = new ModeOption<>(this, "Mode",
            "NCP: no special handling needed. GrimStrict: stop 1 tick before each slot click while moving.",
            Mode.GrimStrict);

    /** One captured handleContainerInput call, replayed verbatim after the freeze. */
    private record DeferredClick(int containerId, int slotId, int buttonNum, ContainerInput input) {}

    // Pending clicks to replay after 1 frozen tick
    private final Deque<DeferredClick> pending = new ArrayDeque<>();

    // Countdown: while > 0, EventInput zeroes movement; hits 0 → replay pending clicks
    private int frozenTicks = 0;

    // Guard: true while flush() is replaying so the mixin doesn't re-queue our own
    // handleContainerInput calls and create an infinite loop.
    private boolean replaying = false;

    public InvMovePlus() {
        super("InvMovePlus", "Bypass inventory-while-moving checks on GrimStrict/NCP servers.");
    }

    @Override
    public void onEnable() {
        pending.clear();
        frozenTicks = 0;
    }

    @Override
    public void onDisable() {
        // Don't silently drop queued clicks; replay them immediately on disable
        flush();
        frozenTicks = 0;
    }

    // ── Zero movement while frozen ─────────────────────────────────────────

    @EventHandler
    private void onInput(EventInput event) {
        if (mode.getValue() != Mode.GrimStrict) return;
        if (frozenTicks <= 0) return;
        event.forward  = false;
        event.backward = false;
        event.left     = false;
        event.right    = false;
        event.jumping  = false;
        // sneaking intentionally kept — doesn't affect GrimStrict's movement check
    }

    // ── Intercept slot clicks while moving ────────────────────────────────

    /**
     * Called from MixinMultiPlayerGameMode at the HEAD of handleContainerInput.
     * Returns true if the click was queued and the original call must be cancelled.
     * Mixin runs regardless of module state, so every bail-out is checked here.
     */
    public boolean deferClick(int containerId, int slotId, int buttonNum, ContainerInput input) {
        if (replaying) return false;
        if (!getState()) return false;
        if (ControlRocket.invMoveBypass) return false;  // ControlRocket chestplate-swap needs precise ordering
        if (mode.getValue() != Mode.GrimStrict) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        // Only defer if the player is actually moving or there are already clicks queued
        // (queue everything once we start so ordering is preserved)
        if (!isMoving(mc) && pending.isEmpty()) return false;

        pending.add(new DeferredClick(containerId, slotId, buttonNum, input));

        // Arm the freeze countdown only if not already running.
        // frozenTicks=2: EventTick.Post of this same tick decrements to 1 (no flush),
        // EventInput of the NEXT tick sees frozenTicks=1 and zeroes movement,
        // EventTick.Post of the next tick decrements to 0 and flushes.
        if (frozenTicks <= 0) frozenTicks = 2;
        return true;
    }

    // ── Countdown and release ─────────────────────────────────────────────

    @EventHandler
    private void onTickPost(EventTick.Post event) {
        if (mode.getValue() != Mode.GrimStrict) return;
        if (frozenTicks <= 0) return;
        frozenTicks--;
        if (frozenTicks == 0) flush();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Replay all pending clicks in order through the real handleContainerInput. */
    private void flush() {
        if (pending.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) { pending.clear(); return; }
        replaying = true;
        try {
            while (!pending.isEmpty()) {
                DeferredClick c = pending.poll();
                // Menu changed (closed/reopened) since the click was captured — replaying
                // against a different menu would mutate the wrong slots locally. Drop it.
                if (mc.player.containerMenu.containerId != c.containerId()) continue;
                mc.gameMode.handleContainerInput(c.containerId(), c.slotId(), c.buttonNum(), c.input(), mc.player);
            }
        } finally {
            replaying = false;
        }
    }

    /**
     * Returns true if the player has meaningful horizontal velocity (was walking/sprinting).
     * Threshold 0.001 m²/tick² ≈ 0.032 m/tick ≈ 0.64 m/s — filters out
     * tiny residual velocity from stopping so standing-still clicks go through immediately.
     */
    private static boolean isMoving(Minecraft mc) {
        return mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.001;
    }
}
