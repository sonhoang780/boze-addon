package com.example.addon.modules;

import com.example.addon.mixin.MixinPlayerLikeEntity;
import com.mojang.authlib.GameProfile;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.InteractionHand;

import java.util.UUID;

/**
 * Dummy — spawns a client-side fake player to test combat / movement in survival.
 *
 * It is a real {@link RemotePlayer} added to the client world, so vanilla renders
 * it exactly like another player (all overlay layers, name tag, held items) and the
 * crosshair can target it.
 *
 * Combat is simulated entirely client-side. The server never sees this entity, so a
 * normal attack click is never resolved as damage; instead {@code MixinMultiPlayerGameMode}
 * hooks {@code attack(Player, Entity)} and calls {@link #onAttacked(Entity)} the instant the
 * local player clicks the dummy — reliable even while spam-clicking (the old attack-strength
 * heuristic silently dropped low-charge hits). We subtract strength-scaled weapon
 * ATTACK_DAMAGE from a local HP pool and fire the totem-of-undying event ((byte) 35)
 * when HP hits 0, until totems run out. Damage does NOT account for the dummy's own armor.
 *
 * Scope note: because the entity is client-only, effects driven purely server-side
 * (real knockback / Wind Burst physics) are illustrative rather than authoritative.
 * On anticheat servers, do NOT spam-attack it: vanilla sends an interact packet for the
 * (unknown) entity id, which can be flagged.
 */
public class Dummy extends AddonModule {
    public static final Dummy INSTANCE = new Dummy();

    // A client-only id far above anything the server will ever assign → no collision.
    private static final int DUMMY_ENTITY_ID = 2_000_000_111;

    public final ToggleOption copyInventory = new ToggleOption(this, "CopyInventory",
            "Copy your current armor and held items onto the dummy.", true);
    public final ToggleOption freeze = new ToggleOption(this, "Freeze",
            "Keep the dummy planted in place (no gravity / knockback drift).", true);
    public final SliderOption totemSlots = new SliderOption(this, "Totems",
            "Number of totems the dummy pops before respawning.", 2.0, 0.0, 10.0, 1.0);
    public final SliderOption invulnDelay = new SliderOption(this, "InvulnDelay",
            "Invulnerability window between hits (ms). Vanilla default = 500 ms (10 ticks).", 500.0, 0.0, 2000.0, 50.0);

    private RemotePlayer dummy;
    private double spawnX, spawnY, spawnZ;
    private float spawnYaw;
    private Pose spawnPose;
    private boolean spawnRequested;
    // The spawn point is captured ONCE (when the module is first enabled). Later
    // respawns (totems exhausted) reuse it instead of teleporting to wherever the
    // player currently stands.
    private boolean hasSpawnPoint;

    // Client-side combat simulation state
    private float dummyHp;
    private int   remainingTotems;
    private long  lastHurtMs = 0; // for invulnerability-frame check

    public Dummy() {
        super("Dummy", "FakePlayer");
    }

    @Override
    public void onEnable() {
        spawnRequested = true;
    }

    @Override
    public void onDisable() {
        removeDummy();
        spawnRequested = false;
        hasSpawnPoint = false; // next enable captures a fresh spawn point
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (dummy != null && dummy.isRemoved()) dummy = null;
        if (spawnRequested || dummy == null) {
            spawnDummy(mc);
            spawnRequested = false;
            return;
        }

        // Keep it planted (and holding its copied pose) when frozen so it's a stable target.
        if (freeze.getValue()) {
            dummy.setDeltaMovement(0, 0, 0);
            dummy.setNoGravity(true);
            dummy.snapTo(spawnX, spawnY, spawnZ, spawnYaw, 0f);
            dummy.setYHeadRot(spawnYaw);
            dummy.setYBodyRot(spawnYaw);
            if (spawnPose != null) dummy.setPose(spawnPose);
        } else {
            // A client-spawned RemotePlayer is normally driven by server position
            // packets, so it never ticks its own physics — that's why an unfrozen
            // dummy used to sit perfectly still, indistinguishable from frozen. Drive
            // gravity + collision manually so it actually falls and keeps any knockback
            // drift applied in onAttacked.
            dummy.setNoGravity(false);
            Vec3 v = dummy.getDeltaMovement();
            double vy = dummy.onGround() ? 0.0 : (v.y - 0.08) * 0.98;
            // Ground friction 0.6 (vanilla on-ground), 0.91 in air — prevents excessive sliding.
            double friction = dummy.onGround() ? 0.6 : 0.91;
            dummy.setDeltaMovement(v.x * friction, vy, v.z * friction);
            dummy.move(MoverType.SELF, dummy.getDeltaMovement());
            dummy.setYHeadRot(dummy.getYRot());
            dummy.setYBodyRot(dummy.getYRot());
        }
    }

    /**
     * Called from {@code MixinMultiPlayerGameMode.attack} the moment the local player
     * attacks an entity. We only react if it is our dummy.
     */
    public void onAttacked(Entity target) {
        if (dummy == null || target == null || target.getId() != DUMMY_ENTITY_ID) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Read weapon modifier directly — mc.player.getAttributeValue(ATTACK_DAMAGE) only
        // returns the base (1.0) because weapon modifiers are not synced to the client
        // attribute map in 26.1.2.
        ItemStack weapon = mc.player.getMainHandItem();
        ItemAttributeModifiers iam = weapon.getComponents().get(DataComponents.ATTRIBUTE_MODIFIERS);
        float dmg = (iam != null)
                ? (float) iam.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND)
                : 1.0f;

        // Vanilla cooldown scaling SQUARES the charge: 0.2 + s² * 0.8 (the old code used a
        // linear 0.2 + 0.8·s, which over-rewarded half-charged spam hits).
        float strength = mc.player.getAttackStrengthScale(0.5f);
        dmg *= 0.2f + strength * strength * 0.8f;

        // Critical: full charge while genuinely falling (airborne, not sprinting/climbing/in water).
        boolean strong = strength > 0.9f;
        boolean crit = strong
                && mc.player.fallDistance > 0.0f
                && !mc.player.onGround()
                && !mc.player.onClimbable()
                && !mc.player.isInWater()
                && !mc.player.isPassenger()
                && !mc.player.isSprinting();
        if (crit) dmg *= 1.5f;

        // Mitigate by the dummy's own worn armour (vanilla CombatRules absorb formula). Note:
        // weapon enchantments (Sharpness…) still aren't applied — those need a ServerLevel.
        dmg = applyArmor(dmg);

        // Invulnerability frames: within the window only the red flash plays, no HP reduction.
        long now = System.currentTimeMillis();
        long window = invulnDelay.getValue().longValue();
        if (window > 0 && (now - lastHurtMs) < window) {
            dummy.handleEntityEvent((byte) 2); // red flash — hit still registered visually
            return;
        }
        lastHurtMs = now;

        applyDamage(mc, dmg);
        playHitFeedback(mc, crit, strong);

        // Vanilla-style knockback: halve existing velocity then add impulse.
        if (!freeze.getValue()) {
            double kx = dummy.getX() - mc.player.getX();
            double kz = dummy.getZ() - mc.player.getZ();
            double klen = Math.sqrt(kx * kx + kz * kz);
            if (klen > 1.0E-4) {
                Vec3 dv = dummy.getDeltaMovement();
                dummy.setDeltaMovement(
                        dv.x / 2.0 + kx / klen * 0.4,
                        Math.min(0.42, dv.y + 0.18),
                        dv.z / 2.0 + kz / klen * 0.4);
            }
        }
    }

    @EventHandler
    private void onPacket(EventPacket.Receive event) {
        if (dummy == null) return;
        if (!(event.packet instanceof ClientboundExplodePacket pkt)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Vec3 center = pkt.center();
        double dist = dummy.position().distanceTo(center);
        double diameter = pkt.radius() * 2.0;
        if (dist >= diameter) return;
        // Vanilla formula: intensity = (1 - dist/diameter) * exposure (assume full exposure = 1).
        double intensity = 1.0 - dist / diameter;
        float dmg = (float) ((intensity * intensity + intensity) / 2.0 * 7.0 * diameter + 1.0);
        applyDamage(mc, dmg);
    }

    /** Vanilla armour absorption: reduces damage by the dummy's worn ARMOR / ARMOR_TOUGHNESS. */
    private float applyArmor(float damage) {
        double armor = 0.0, toughness = 0.0;
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack piece = dummy.getItemBySlot(slot);
            ItemAttributeModifiers piam = piece.getComponents().get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (piam == null) continue;
            armor     += piam.compute(Attributes.ARMOR, 0.0, slot);
            toughness += piam.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot);
        }
        if (armor <= 0.0) return damage;
        double f = 2.0 + toughness / 4.0;
        double g = Mth.clamp(armor - damage / f, armor * 0.2, 20.0);
        return (float) (damage * (1.0 - g / 25.0));
    }

    /** Replicate the attacker-side hit sound + crit particles — the server never resolves this hit,
     *  so without this the dummy loses HP silently. */
    private void playHitFeedback(Minecraft mc, boolean crit, boolean strong) {
        SoundEvent sound = crit ? SoundEvents.PLAYER_ATTACK_CRIT
                : strong ? SoundEvents.PLAYER_ATTACK_STRONG
                : SoundEvents.PLAYER_ATTACK_WEAK;
        mc.level.playLocalSound(dummy.getX(), dummy.getY(), dummy.getZ(),
                sound, SoundSource.PLAYERS, 1.0f, 1.0f, false);
        if (crit) {
            var rnd = dummy.getRandom();
            for (int i = 0; i < 12; i++) {
                mc.level.addParticle(ParticleTypes.CRIT,
                        dummy.getX() + (rnd.nextDouble() - 0.5) * dummy.getBbWidth(),
                        dummy.getY() + rnd.nextDouble() * dummy.getBbHeight(),
                        dummy.getZ() + (rnd.nextDouble() - 0.5) * dummy.getBbWidth(),
                        rnd.nextGaussian() * 0.1, rnd.nextGaussian() * 0.1, rnd.nextGaussian() * 0.1);
            }
        }
    }

    private void applyDamage(Minecraft mc, float dmg) {
        dummyHp -= dmg;
        dummy.handleEntityEvent((byte) 2);  // red hurt flash
        dummy.setHealth(Math.max(0f, dummyHp));
        if (dummyHp <= 0f) checkDeath(mc);
    }

    private void checkDeath(Minecraft mc) {
        if (remainingTotems > 0) {
            remainingTotems--;
            dummyHp = 20f;
            dummy.setHealth(20f);
            // In 26.1.2 the totem effect is handled in ClientPacketListener (not entity.handleEntityEvent),
            // so we replicate the visual and sound directly.
            mc.particleEngine.createTrackingEmitter(dummy, ParticleTypes.TOTEM_OF_UNDYING, 30);
            mc.level.playLocalSound(dummy.getX(), dummy.getY(), dummy.getZ(),
                    SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.0f, false);
            if (remainingTotems == 0) {
                dummy.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                dummy.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            }
        } else {
            spawnRequested = true;
        }
    }

    private void spawnDummy(Minecraft mc) {
        removeDummy();

        dummyHp = 20f;
        remainingTotems = totemSlots.getValue().intValue();
        lastHurtMs = 0;

        // Capture the spawn point only the FIRST time (when the module is enabled).
        // Respawns after the totems run out reuse it, so the dummy never teleports to
        // wherever the player has since walked to.
        if (!hasSpawnPoint) {
            spawnX = mc.player.getX();
            spawnY = mc.player.getY();
            spawnZ = mc.player.getZ();
            spawnYaw = mc.player.getYRot();
            spawnPose = mc.player.getPose();
            hasSpawnPoint = true;
        }

        UUID uuid = UUID.randomUUID();
        GameProfile profile = new GameProfile(uuid, "Dummy");

        RemotePlayer d = new RemotePlayer(mc.level, profile);
        d.setId(DUMMY_ENTITY_ID);
        d.setUUID(uuid);
        d.snapTo(spawnX, spawnY, spawnZ, spawnYaw, 0f);
        d.setYHeadRot(spawnYaw);
        d.setYBodyRot(spawnYaw);

        // Mirror the player's pose so e.g. a swimming/crawling player spawns a matching dummy.
        d.setPose(spawnPose);
        d.setSwimming(mc.player.isSwimming());
        d.setSprinting(mc.player.isSprinting());
        d.setShiftKeyDown(mc.player.isShiftKeyDown());

        if (copyInventory.getValue()) {
            // Mirror every armor slot.
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                d.setItemSlot(slot, mc.player.getItemBySlot(slot).copy());
            }
            // Mirror held items; if the off hand is empty, give a totem so the dummy can pop.
            d.setItemInHand(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().copy());
            ItemStack offhand = mc.player.getOffhandItem();
            d.setItemInHand(InteractionHand.OFF_HAND,
                    offhand.isEmpty() ? new ItemStack(Items.TOTEM_OF_UNDYING) : offhand.copy());
        } else {
            d.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            d.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        }

        // Enable every skin overlay layer (hat / jacket / sleeves / pants).
        d.getEntityData().set(MixinPlayerLikeEntity.getModelCustomizationData(), (byte) 0x7F);

        if (freeze.getValue()) d.setNoGravity(true);
        d.setHealth(20f);

        mc.level.addEntity(d);
        dummy = d;
    }

    private void removeDummy() {
        Minecraft mc = Minecraft.getInstance();
        if (dummy != null) {
            if (mc.level != null) mc.level.removeEntity(dummy.getId(), Entity.RemovalReason.DISCARDED);
            dummy.discard();
            dummy = null;
        }
    }
}
