package com.example.addon.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Entity's protected shared-flag machinery so EBouncePlus can set the local
 * FALL_FLYING flag immediately after triggering a recast, instead of waiting a tick (or
 * more, under latency) for the server to echo the flag back after START_FALL_FLYING is
 * accepted. Mirrors lambda-client's BounceElytraFly, which calls a local startGliding()
 * for the same reason: without it, isFallFlying() reads false for the gap between the
 * jump and the echo, so the pitch-override in EBouncePlus reverts to the real camera
 * angle for that window -- visually "standing up" instead of diving through the bounce.
 */
@Mixin(Entity.class)
public interface EntityFlagAccessor {
    @Accessor("FLAG_FALL_FLYING")
    static int getFlagFallFlying() { throw new AssertionError(); }

    @Invoker("setSharedFlag")
    void invokeSetSharedFlag(int flag, boolean value);
}
