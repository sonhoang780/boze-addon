package com.example.addon.mixin;

import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.entity.data.TrackedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the protected static {@code PLAYER_MODE_CUSTOMIZATION_ID} tracked-data key so the
 * Dummy module can enable all skin overlay layers (hat / jacket / sleeves / pants) on its
 * fake player, making it render identically to a real player.
 *
 * Reflection by the Yarn field name would break at runtime (intermediary remap); a Mixin
 * @Accessor resolves the field name correctly for 1.21.11.
 */
@Mixin(PlayerLikeEntity.class)
public interface MixinPlayerLikeEntity {
    @Accessor("PLAYER_MODE_CUSTOMIZATION_ID")
    static TrackedData<Byte> getModelCustomizationData() {
        throw new AssertionError();
    }
}
