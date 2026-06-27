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
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * MaceAura — Cách 4 (two-tick fold → strike). Verified against decompiled MC 26.1.2.
 *
 * THE PROBLEM (canSmashAttack, MaceItem:148):
 *     return attacker.fallDistance > 1.5 && !attacker.isFallFlying();
 * Both must hold on the SERVER at the instant the attack packet is processed.
 *
 * WHY ONE-TICK SPOOFS FAIL (confirmed by video: mace confiscated, no damage):
 *   • fallDistance is updated SYNCHRONOUSLY during packet handling
 *     (ServerGamePacketListenerImpl.handleMovePlayer → doCheckFallDamage →
 *      checkFallDamage: `if (ya < 0) fallDistance -= ya`, Entity:1487).
 *   • isFallFlying (shared flag 7) is updated ONLY in aiStep → updateFallFlying
 *     (LivingEntity:3034), which runs AFTER the whole packet batch each server tick.
 *   • So if StatusOnly(onGround=true) and the attack are in the SAME tick/batch,
 *     updateFallFlying hasn't run yet → isFallFlying is still true at attack time →
 *     canSmashAttack=false AND 6b6t confiscates the mace.
 *
 * THE FIX — split across two server ticks:
 *   FOLD tick:  send StatusOnly(onGround=true) as the LAST packet of the tick
 *               (EventTick.Post fires after the vanilla move packet, so onGround=true
 *               is the value the server keeps for this tick).
 *               → end-of-tick aiStep: updateFallFlying sees onGround=true →
 *                 canGlide()=false (LivingEntity:3122 needs !onGround) →
 *                 setSharedFlag(7,false). isFallFlying is now FALSE server-side and
 *                 will NOT auto-restart (updateFallFlying never sets it true; only the
 *                 START_FALL_FLYING command does).
 *   wait foldDelay ticks  (guards against packet bunching under latency, so the
 *               server definitely runs aiStep between FOLD and STRIKE).
 *   STRIKE tick: send PosRot dropping Y by (threshold+0.2), onGround=false →
 *               doCheckFallDamage adds (threshold+0.2) to fallDistance synchronously,
 *               isFallFlying still false → attack → canSmashAttack TRUE → SMASH.
 *               Then START_FALL_FLYING to resync the server's glide state to the client
 *               (which never stopped gliding) so altitude is kept and no fall damage.
 *
 * The CLIENT never moves — only fake packets are sent — so ElytraFly holds altitude.
 * Y is exempt from the server "moved wrongly" check (SGPLI:1139, yDist always zeroed),
 * so the Y-spoof never rubber-bands. XZSpoof additionally fakes X/Z to the target so the
 * reach gate (isWithinEntityInteractionRange, SGPLI:1824) trivially passes.
 */
public class MaceAura extends AddonModule {
    public static final MaceAura INSTANCE = new MaceAura();

    public final SliderOption range          = new SliderOption(this, "Range",          "Horizontal attack range (blocks).", 4.0, 1.0, 8.0, 0.5);
    public final SliderOption approachRange  = new SliderOption(this, "ApproachRange",  "Horizontal acquisition radius.", 24.0, 6.0, 50.0, 1.0);
    public final SliderOption vertRange      = new SliderOption(this, "VerticalRange",  "Max Y-delta above a target.", 20.0, 5.0, 30.0, 1.0);
    public final SliderOption minHeight      = new SliderOption(this, "MinHeight",      "Min Y above target before folding.", 2.5, 1.0, 15.0, 0.5);
    public final SliderOption smashThreshold = new SliderOption(this, "SmashThreshold", "Spoofed fall distance (server min = 1.5). Higher = more damage but reach drops.", 1.7, 1.6, 6.0, 0.1);
    public final SliderOption foldDelay      = new SliderOption(this, "FoldDelay",      "Ticks to wait after the fold packet before striking (≥2 so server clears fall-flying). Raise if high ping.", 2.0, 1.0, 8.0, 1.0);
    public final SliderOption attackDelay    = new SliderOption(this, "Delay",          "ms between attack cycles.", 250.0, 50.0, 2000.0, 50.0);
    public final ToggleOption autoTarget     = new ToggleOption(this, "AutoTarget",     "Auto-pick nearest player in range (no LoS).", true);
    public final ToggleOption silentSwap     = new ToggleOption(this, "SilentSwap",     "Swap to mace silently for the hit, swap back.", true);
    public final ToggleOption attributeSwap  = new ToggleOption(this, "AttributeSwap",  "Pre-attack with sword for extended reach before mace smash.", false);
    public final ToggleOption xzSpoof        = new ToggleOption(this, "XZSpoof",        "Fake X/Z to the target on the strike packet so the reach gate always passes.", true);

    public final ToggleOption elytraFly = new ToggleOption(this, "ElytraFly", "Velocity-based elytra flight. Needs elytra deployed.", true);
    public final SliderOption flySpeed  = new SliderOption(this, "FlySpeed",  "Horizontal cruise speed (blocks/tick).", 1.0, 0.2, 2.5, 0.1);
    public final SliderOption vertSpeed = new SliderOption(this, "VertSpeed", "Up/Down speed with Space/Shift (blocks/tick).", 0.8, 0.2, 2.0, 0.1);

    private long lastAttackMs = 0;

    private enum DipState { IDLE, APPROACH, FOLD, STRIKE }
    private DipState dipState  = DipState.IDLE;
    private int      stateTicks;
    private AbstractClientPlayer dipTarget;

    public MaceAura() {
        super("MaceAura", "Mace smash via two-tick fold→strike spoof. Server-side fallDistance+!isFallFlying, altitude kept.");
    }

    @Override public void onEnable()  { lastAttackMs = System.currentTimeMillis(); resetIdle(); }
    @Override public void onDisable() { resetIdle(); }

    private void resetIdle() {
        dipState   = DipState.IDLE;
        dipTarget  = null;
        stateTicks = 0;
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.getConnection() == null) return;

        // Abort the cycle if the client wings close unexpectedly (we rely on the client
        // still gliding to hold altitude through FOLD/STRIKE).
        if (!mc.player.isFallFlying() && (dipState == DipState.APPROACH || dipState == DipState.FOLD)) {
            resetIdle();
            return;
        }

        // ── APPROACH ──────────────────────────────────────────────────────────
        if (dipState == DipState.APPROACH) {
            steer(mc);
            stateTicks++;
            if (dipTarget == null || dipTarget.isRemoved()) { resetIdle(); return; }
            double dy = mc.player.getY() - dipTarget.getY();
            if (dy < minHeight.getValue() || dy > vertRange.getValue() || stateTicks > 200) { resetIdle(); return; }
            if (hDist(mc.player, dipTarget) <= range.getValue()) beginFold(mc);
            return;
        }

        // ── FOLD ─ wait foldDelay ticks for the server to clear fall-flying ──────
        if (dipState == DipState.FOLD) {
            steer(mc);                       // keep gliding so altitude holds
            stateTicks++;
            if (dipTarget == null || dipTarget.isRemoved()) { resetIdle(); return; }
            if (stateTicks >= (int)(double) foldDelay.getValue()) {
                dipState   = DipState.STRIKE;
                stateTicks = 0;
            }
            return;
        }

        // ── STRIKE ─ one tick: fake the fall, attack, resync ─────────────────────
        if (dipState == DipState.STRIKE) {
            if (dipTarget != null && !dipTarget.isRemoved()) {
                doStrike(mc, dipTarget);
                lastAttackMs = System.currentTimeMillis();
            }
            resetIdle();
            return;
        }

        // ── IDLE ─────────────────────────────────────────────────────────────
        steer(mc);

        if (silentSwap.getValue()) {
            if (InvHelper.findInHotbar(Items.MACE) == -1) return;
        } else if (mc.player.getMainHandItem().getItem() != Items.MACE) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAttackMs < attackDelay.getValue().longValue()) return;

        AbstractClientPlayer target = findTarget(mc);
        if (target == null) return;

        double dy = mc.player.getY() - target.getY();
        double hd = hDist(mc.player, target);

        // Passive: grounded + natural fall already enough → straight hit.
        if (!mc.player.isFallFlying() && hd <= range.getValue() && dy >= 0
                && mc.player.fallDistance >= smashThreshold.getValue()) {
            doAttack(mc, target);
            lastAttackMs = now;
            return;
        }

        if (!elytraFly.getValue() || !mc.player.isFallFlying()) return;
        if (dy < minHeight.getValue()) return;

        dipTarget  = target;
        stateTicks = 0;
        if (hd <= range.getValue()) {
            beginFold(mc);
        } else {
            dipState = DipState.APPROACH;
        }
    }

    // ── Spoof sequence ────────────────────────────────────────────────────────

    /**
     * FOLD: send ONE StatusOnly(onGround=true) — the last movement packet of this
     * client tick (EventTick.Post runs after player.tick() flushed the vanilla packet),
     * so the server keeps onGround=true for the tick and clears fall-flying in aiStep.
     */
    private void beginFold(Minecraft mc) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
        dipState   = DipState.FOLD;
        stateTicks = 0;
    }

    /**
     * STRIKE: drop the server-perceived position by (threshold+0.2) so doCheckFallDamage
     * accumulates that as fallDistance this tick, then attack while isFallFlying is still
     * false (cleared back in FOLD's aiStep). Finally re-enable gliding to resync.
     */
    private void doStrike(Minecraft mc, AbstractClientPlayer target) {
        double realX = mc.player.getX();
        double realY = mc.player.getY();
        double realZ = mc.player.getZ();
        float  yaw   = mc.player.getYRot();
        float  pitch = mc.player.getXRot();

        double drop  = smashThreshold.getValue() + 0.2;
        double fakeY = realY - drop;
        // XZSpoof: stand the server-perceived position right on the target → reach passes.
        double fakeX = xzSpoof.getValue() ? target.getX() : realX;
        double fakeZ = xzSpoof.getValue() ? target.getZ() : realZ;

        // The downward (and optional horizontal) teleport. onGround=false so fallDistance
        // accrues; Y is exempt from the "moved wrongly" check so this never rubber-bands.
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(fakeX, fakeY, fakeZ, yaw, pitch, false, false));

        // Attack — server now sees fallDistance>1.5 && !isFallFlying → SMASH.
        doAttack(mc, target);

        // Resync: client never stopped gliding, so tell the server to resume fall-flying.
        // The next vanilla packet (real, higher Y) is then accepted as elytra climb and
        // resets the server fallDistance — altitude is preserved, no fall damage.
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    // ── Attack ──────────────────────────────────────────────────────────────

    private void doAttack(Minecraft mc, AbstractClientPlayer target) {
        if (attributeSwap.getValue()) {
            int swordSlot = findSwordInHotbar(mc);
            if (swordSlot != -1) {
                boolean needSwap = swordSlot != mc.player.getInventory().getSelectedSlot();
                if (needSwap) InvHelper.swapToSlot(swordSlot, SwapType.Normal);
                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);
                if (needSwap) InvHelper.swapBack();
            }
        }

        boolean silent   = silentSwap.getValue();
        int     maceSlot = silent ? InvHelper.findInHotbar(Items.MACE) : -1;
        if (silent && maceSlot == -1) return;

        boolean alreadySelected = maceSlot != -1 && maceSlot == mc.player.getInventory().getSelectedSlot();
        boolean swapped = silent && !alreadySelected && InvHelper.swapToSlot(maceSlot, SwapType.Normal);

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);

        if (swapped) InvHelper.swapBack();
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

    // ── ElytraFly steering ──────────────────────────────────────────────────

    private void steer(Minecraft mc) {
        if (!elytraFly.getValue() || !mc.player.isFallFlying()) return;

        boolean autoHoriz = dipTarget != null && (dipState == DipState.APPROACH || dipState == DipState.FOLD);
        double vx = 0, vz = 0;
        double speed = flySpeed.getValue();

        if (autoHoriz) {
            double dx = dipTarget.getX() - mc.player.getX();
            double dz = dipTarget.getZ() - mc.player.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) { vx = dx / len * speed; vz = dz / len * speed; }
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
        }

        // Hold altitude during the auto cycle so the spoof keeps a stable reference Y.
        double vy;
        if (dipState == DipState.APPROACH || dipState == DipState.FOLD) vy = 0.0;
        else if (mc.options.keyJump.isDown())  vy =  vertSpeed.getValue();
        else if (mc.options.keyShift.isDown()) vy = -vertSpeed.getValue();
        else                                    vy = 0.0;

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
