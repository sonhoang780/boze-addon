package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * InvMovePlus — lets you interact with your inventory while walking on strict servers.
 *
 * NCP mode   : no-op (NCP doesn't block inv-while-moving the way Grim does).
 * GrimStrict : intercepts every ClickSlotC2SPacket while the player is moving,
 *              zeros movement input for 1 tick so the server sees the player stop,
 *              then re-sends the queued click(s).  This prevents the
 *              "InventoryOpen while moving" flag that GrimAC raises.
 *
 * Tick flow (GrimStrict):
 *   Tick N  — EventPacket.Send fires: ClickSlotC2SPacket cancelled, queued, frozenTicks=2
 *             EventTick.Post: frozenTicks 2→1 (nothing sent yet)
 *   Tick N+1 — EventInput fires: frozenTicks=1 → forward/backward/left/right/jumping zeroed
 *              player physics runs with 0 input → sends 0-velocity PlayerMoveC2SPacket
 *              EventTick.Post: frozenTicks 1→0 → pending packets flushed
 *   Grim receives: [tick-N move packet | tick-N+1 move packet with 0 velocity | ClickSlotC2SPacket]
 */
public class InvMovePlus extends AddonModule {
    public static final InvMovePlus INSTANCE = new InvMovePlus();

    public enum Mode { NCP, GrimStrict }

    public final ModeOption<Mode> mode = new ModeOption<>(this, "Mode",
            "NCP: no special handling needed. GrimStrict: stop 1 tick before each slot click while moving.",
            Mode.GrimStrict);

    // Pending ClickSlotC2SPackets to re-send after 1 frozen tick
    private final Deque<Packet<?>> pending = new ArrayDeque<>();

    // Countdown: while > 0, EventInput zeroes movement; hits 0 → flush pending packets
    private int frozenTicks = 0;

    // Guard: true while flush() is sending packets so onPacketSend() doesn't
    // re-intercept our own outgoing packets and create an infinite loop.
    private boolean flushing = false;

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
        // Don't silently drop queued packets; send them immediately on disable
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

    @EventHandler
    private void onPacketSend(EventPacket.Send event) {
        if (flushing) return;
        if (FakeFly.invMoveBypass) return;  // FakeFly chestplate-swap needs precise ordering
        if (mode.getValue() != Mode.GrimStrict) return;
        if (!(event.packet instanceof ClickSlotC2SPacket)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Only defer if the player is actually moving or there are already packets queued
        // (queue everything once we start so ordering is preserved)
        if (!isMoving(mc) && pending.isEmpty()) return;

        event.cancel();
        pending.add(event.packet);

        // Arm the freeze countdown only if not already running.
        // frozenTicks=2: EventTick.Post of this same tick decrements to 1 (no flush),
        // EventInput of the NEXT tick sees frozenTicks=1 and zeroes movement,
        // EventTick.Post of the next tick decrements to 0 and flushes.
        if (frozenTicks <= 0) frozenTicks = 2;
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

    /** Send all pending packets in order and clear the queue. */
    private void flush() {
        if (pending.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) { pending.clear(); return; }
        flushing = true;
        try {
            while (!pending.isEmpty()) {
                mc.getNetworkHandler().sendPacket(pending.poll());
            }
        } finally {
            flushing = false;
        }
    }

    /**
     * Returns true if the player has meaningful horizontal velocity (was walking/sprinting).
     * Threshold 0.001 m²/tick² ≈ 0.032 m/tick ≈ 0.64 m/s — filters out
     * tiny residual velocity from stopping so standing-still clicks go through immediately.
     */
    private static boolean isMoving(MinecraftClient mc) {
        return mc.player.getVelocity().horizontalLengthSquared() > 0.001;
    }
}
