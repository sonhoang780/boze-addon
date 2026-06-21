package com.example.addon.modules;

import com.example.addon.mixin.MixinPlayerLikeEntity;
import com.mojang.authlib.GameProfile;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.UUID;

/**
 * Dummy — spawns a client-side fake player to test combat / movement in survival.
 *
 * It is a real {@link OtherClientPlayerEntity} added to the client world, so vanilla renders
 * it exactly like another player (skin, all overlay layers, name tag, held items) and the
 * crosshair can target & attack it. It always holds a Totem of Undying.
 *
 * Scope note: the entity is client-only — the server never sees it. On single-player the
 * integrated server still won't process hits on a client-spawned entity, so effects driven
 * purely server-side (e.g. a Wind Burst launch) are illustrative of how a real player would
 * look/react rather than authoritative physics. On anticheat servers, do NOT spam-attack it:
 * vanilla sends an interact packet for the (unknown) entity id, which can be flagged.
 */
public class Dummy extends AddonModule {
    public static final Dummy INSTANCE = new Dummy();

    // A client-only id far above anything the server will ever assign → no collision.
    private static final int DUMMY_ENTITY_ID = 2_000_000_111;

    public final ToggleOption copySkin = new ToggleOption(this, "Copy My Skin",
            "Use your own skin so the dummy looks exactly like you. Off = default skin.", true);
    public final SliderOption distance = new SliderOption(this, "Distance",
            "How many blocks in front of you to spawn the dummy.", 3.0, 1.0, 6.0, 0.5);
    public final ToggleOption freeze = new ToggleOption(this, "Freeze",
            "Keep the dummy planted in place (no gravity / knockback drift). Off = reacts naturally.", true);

    private OtherClientPlayerEntity dummy;
    private double spawnX, spawnY, spawnZ;
    private float spawnYaw;
    private boolean spawnRequested;

    public Dummy() {
        super("Dummy", "Spawns a life-like fake player (always holding a totem) to test combat in survival.");
    }

    @Override
    public void onEnable() {
        spawnRequested = true;
    }

    @Override
    public void onDisable() {
        removeDummy();
        spawnRequested = false;
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // (Re)spawn if requested, or if the previous dummy was removed for any reason.
        if (dummy != null && dummy.isRemoved()) dummy = null;
        if (spawnRequested || dummy == null) {
            spawnDummy(mc);
            spawnRequested = false;
            return;
        }

        // Keep it planted when frozen so it's a stable target.
        if (freeze.getValue()) {
            dummy.setVelocity(0, 0, 0);
            dummy.setNoGravity(true);
            dummy.refreshPositionAndAngles(spawnX, spawnY, spawnZ, spawnYaw, 0f);
            dummy.setHeadYaw(spawnYaw);
            dummy.setBodyYaw(spawnYaw);
        } else {
            dummy.setNoGravity(false);
        }
    }

    private void spawnDummy(MinecraftClient mc) {
        removeDummy();

        // Position: `distance` blocks in the direction the player is facing, facing back at the player.
        double yawRad = Math.toRadians(mc.player.getYaw());
        double dist = distance.getValue();
        spawnX = mc.player.getX() - Math.sin(yawRad) * dist;
        spawnY = mc.player.getY();
        spawnZ = mc.player.getZ() + Math.cos(yawRad) * dist;
        spawnYaw = mc.player.getYaw() + 180f;

        UUID uuid = UUID.randomUUID();
        GameProfile profile = new GameProfile(uuid, "Dummy");
        if (copySkin.getValue()) {
            // Copy the signed texture property → dummy renders with your skin (the embedded
            // skin URL is independent of the profile UUID, so a fresh UUID still works).
            profile.properties().putAll(mc.player.getGameProfile().properties());
        }

        OtherClientPlayerEntity d = new OtherClientPlayerEntity(mc.world, profile);
        d.setId(DUMMY_ENTITY_ID);
        d.setUuid(uuid);
        d.refreshPositionAndAngles(spawnX, spawnY, spawnZ, spawnYaw, 0f);
        d.setHeadYaw(spawnYaw);
        d.setBodyYaw(spawnYaw);

        // Always hold a totem (main hand = clearly visible, off hand = realistic survival carry).
        d.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        d.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));

        // Enable every skin overlay layer (hat / jacket / sleeves / pants) so it looks like a real player.
        d.getDataTracker().set(MixinPlayerLikeEntity.getModelCustomizationData(), (byte) 0x7F);

        if (freeze.getValue()) d.setNoGravity(true);

        mc.world.addEntity(d);
        dummy = d;
    }

    private void removeDummy() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (dummy != null) {
            if (mc.world != null) mc.world.removeEntity(dummy.getId(), Entity.RemovalReason.DISCARDED);
            dummy.discard();
            dummy = null;
        }
    }
}
