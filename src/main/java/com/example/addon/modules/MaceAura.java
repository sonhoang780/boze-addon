package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * MaceAura v2 — pure flight hover + single-tick strike. Spec:
 * docs/superpowers/specs/2026-07-02-maceaura-v2-design.md
 *
 * canSmashAttack (server, 26.1.2): fallDistance > 1.5 && !isFallFlying().
 * v2 NEVER glides server-side (no START_FALL_FLYING, no onGround spoof), so
 * !isFallFlying() is permanently true and 6b6t's gliding-mace confiscation
 * can't fire. fallDistance comes from the strike packet itself: handleMovePlayer
 * credits (dy < 0) drops synchronously, before the attack packet in the same
 * batch is processed. One PosRot does three jobs: reach, fallDistance, damage.
 *
 * Attack is a hand-crafted ServerboundAttackPacket — mc.gameMode.attack would
 * call ensureHasSentCarriedItem and re-sync the real hotbar slot, defeating
 * SwapType.Silent (hit would land with the wrong item).
 */
public class MaceAura extends AddonModule {
    public static final MaceAura INSTANCE = new MaceAura();

    public final SliderOption range         = new SliderOption(this, "Range",         "Horizontal attack range (blocks).", 4.0, 1.0, 8.0, 0.5);
    public final SliderOption approachRange = new SliderOption(this, "ApproachRange", "Horizontal acquisition radius.", 24.0, 6.0, 50.0, 1.0);
    public final SliderOption vertRange     = new SliderOption(this, "VerticalRange", "Max Y-delta above a target.", 20.0, 5.0, 30.0, 1.0);
    public final SliderOption minHeight     = new SliderOption(this, "MinHeight",     "Min Y above target to strike. 6b6t deals full smash pain above ~6.", 6.5, 1.0, 15.0, 0.5);
    public final SliderOption hoverHeight   = new SliderOption(this, "HoverHeight",   "Auto-hover altitude above the target (blocks).", 7.0, 6.5, 10.0, 0.5);
    public final SliderOption strikeGap     = new SliderOption(this, "StrikeGap",     "Server-side Y above the target on the strike packet. Lower = closer/safer reach, higher = less fallDistance.", 2.0, 1.5, 3.5, 0.1);
    public final SliderOption attackDelay   = new SliderOption(this, "Delay",         "ms between attack cycles.", 250.0, 50.0, 2000.0, 50.0);
    public final ToggleOption autoTarget    = new ToggleOption(this, "AutoTarget",    "Auto-pick nearest player in range (no LoS).", true);
    public final ToggleOption silentSwap    = new ToggleOption(this, "SilentSwap",    "Swap to mace silently (packet-only) for the hit.", true);
    public final ToggleOption attributeSwap = new ToggleOption(this, "AttributeSwap", "Pre-attack with sword for base damage before the mace smash.", false);

    public final SliderOption flySpeed  = new SliderOption(this, "FlySpeed",  "Horizontal cruise speed (blocks/tick).", 1.0, 0.2, 2.5, 0.1);
    public final SliderOption vertSpeed = new SliderOption(this, "VertSpeed", "Vertical speed (blocks/tick), also caps auto-hover correction.", 0.8, 0.2, 2.0, 0.1);

    private long lastAttackMs = 0;

    public MaceAura() {
        super("MaceAura", "Flight hover + single-tick strike. Never glides server-side, so the mace never gets confiscated.");
    }

    @Override public void onEnable()  { lastAttackMs = System.currentTimeMillis(); }
    @Override public void onDisable() {}

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        AbstractClientPlayer target = findTarget(mc);
        steer(mc, target);

        if (silentSwap.getValue()) {
            if (InvHelper.findInHotbar(Items.MACE) == -1) return;
        } else if (mc.player.getMainHandItem().getItem() != Items.MACE) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAttackMs < attackDelay.getValue().longValue()) return;
        if (target == null) return;

        double dy = mc.player.getY() - target.getY();
        double hd = hDist(mc.player, target);

        // Passive path: naturally falling with real fallDistance already qualifying —
        // plain hit, no spoof needed.
        if (!mc.player.onGround() && mc.player.fallDistance > 1.5
                && hd <= range.getValue() && dy >= 0 && dy < minHeight.getValue()) {
            doAttack(mc, target);
            lastAttackMs = now;
            return;
        }

        if (dy < minHeight.getValue() || dy > vertRange.getValue()) return;
        if (hd > range.getValue()) return; // steer() is still closing distance

        strike(mc, target);
        lastAttackMs = now;
    }

    // ── Strike: one tick, one spoofed PosRot, one attack packet ────────────────

    /**
     * Server position drops to (target.x, target.y + strikeGap, target.z):
     *  - reach gate passes (server pos is within interaction range of the target),
     *  - fallDistance is credited (dy - strikeGap) synchronously by handleMovePlayer,
     *  - smash damage scales with that credited fall.
     * Y is exempt from the "moved wrongly" check; the next vanilla movement packet
     * (real position) walks the server back up. Nothing else is sent — no
     * START_FALL_FLYING, no StatusOnly.
     */
    private void strike(Minecraft mc, AbstractClientPlayer target) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
            target.getX(), target.getY() + strikeGap.getValue(), target.getZ(),
            mc.player.getYRot(), mc.player.getXRot(), false, false));
        doAttack(mc, target);
    }

    /**
     * Silent-swap + hand-crafted attack packet. NEVER route through
     * mc.gameMode.attack here — ensureHasSentCarriedItem would re-sync the real
     * hotbar slot and the hit would land with the wrong item.
     */
    private void doAttack(Minecraft mc, AbstractClientPlayer target) {
        if (attributeSwap.getValue()) {
            int swordSlot = findSwordInHotbar(mc);
            if (swordSlot != -1) {
                boolean swapped = InvHelper.swapToSlot(swordSlot, SwapType.Silent);
                mc.getConnection().send(new ServerboundAttackPacket(target.getId()));
                mc.player.swing(InteractionHand.MAIN_HAND);
                if (swapped) InvHelper.swapBack();
            }
        }

        if (silentSwap.getValue()) {
            int maceSlot = InvHelper.findInHotbar(Items.MACE);
            if (maceSlot == -1) return;
            boolean swapped = InvHelper.swapToSlot(maceSlot, SwapType.Silent);
            mc.getConnection().send(new ServerboundAttackPacket(target.getId()));
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (swapped) InvHelper.swapBack();
        } else {
            mc.getConnection().send(new ServerboundAttackPacket(target.getId()));
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private int findSwordInHotbar(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            var s = mc.player.getInventory().getItem(i);
            if (s.isEmpty() || s.getItem() == Items.MACE) continue;
            ItemAttributeModifiers iam = s.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (iam == null) continue;
            if (iam.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND) > 1.0) return i;
        }
        return -1;
    }

    // ── Flight hover (no isFallFlying gate — this is NOT elytra flight) ────────

    private void steer(Minecraft mc, AbstractClientPlayer target) {
        // Only fly while airborne; on the ground vanilla movement stays untouched.
        if (mc.player.onGround()) return;

        double vx = 0, vz = 0, vy;
        double speed = flySpeed.getValue();

        boolean auto = target != null;
        if (auto) {
            double dx = target.getX() - mc.player.getX();
            double dz = target.getZ() - mc.player.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            // Close horizontal distance until well inside attack range, then hold.
            if (len > range.getValue() * 0.6) { vx = dx / len * speed; vz = dz / len * speed; }
            // Hold player.y = target.y + hoverHeight (correction capped at vertSpeed).
            double err = (target.getY() + hoverHeight.getValue()) - mc.player.getY();
            vy = Math.max(-vertSpeed.getValue(), Math.min(vertSpeed.getValue(), err));
        } else {
            double yaw = Math.toRadians(mc.player.getYRot());
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            double dx = 0, dz = 0;
            if (mc.options.keyUp.isDown())    { dx -= sin; dz += cos; }
            if (mc.options.keyDown.isDown())  { dx += sin; dz -= cos; }
            if (mc.options.keyLeft.isDown())  { dx += cos; dz += sin; }
            if (mc.options.keyRight.isDown()) { dx -= cos; dz -= sin; }
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) { vx = dx / len * speed; vz = dz / len * speed; }

            if (mc.options.keyJump.isDown())       vy =  vertSpeed.getValue();
            else if (mc.options.keyShift.isDown()) vy = -vertSpeed.getValue();
            else                                    vy = 0.0;
        }

        mc.player.setDeltaMovement(vx, vy, vz);
    }

    // ── Target selection ────────────────────────────────────────────────────

    private AbstractClientPlayer findTarget(Minecraft mc) {
        double r  = approachRange.getValue();
        double vr = vertRange.getValue();

        if (!autoTarget.getValue()) {
            if (mc.crosshairPickEntity instanceof AbstractClientPlayer p) {
                double dy = mc.player.getY() - p.getY();
                if (hDist(mc.player, p) <= r && dy >= 0 && dy <= vr) return p;
            }
            return null;
        }

        AbstractClientPlayer best     = null;
        double               bestDist = Double.MAX_VALUE;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            if (p.isRemoved()) continue;
            double dy = mc.player.getY() - p.getY();
            if (dy < 0 || dy > vr) continue;
            double hd = hDist(mc.player, p);
            if (hd > r) continue;
            if (hd < bestDist) { bestDist = hd; best = p; }
        }
        return best;
    }

    private static double hDist(net.minecraft.world.entity.Entity a, net.minecraft.world.entity.Entity b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
