package com.example.addon.mixin;

import net.minecraft.world.entity.Avatar;
import net.minecraft.network.syncher.EntityDataAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the protected static {@code DATA_PLAYER_MODE_CUSTOMISATION} entity-data key so the
 * Dummy module can enable all skin overlay layers (hat / jacket / sleeves / pants) on its
 * fake player, making it render identically to a real player.
 *
 * Reflection by field name would break at runtime under obfuscation; a Mixin
 * @Accessor resolves the field correctly for 26.1.2 (which ships unobfuscated, but this
 * stays robust either way).
 */
@Mixin(Avatar.class)
public interface MixinPlayerLikeEntity {
    @Accessor("DATA_PLAYER_MODE_CUSTOMISATION")
    static EntityDataAccessor<Byte> getModelCustomizationData() {
        throw new AssertionError();
    }
}
