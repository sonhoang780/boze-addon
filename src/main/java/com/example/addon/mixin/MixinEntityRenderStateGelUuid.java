package com.example.addon.mixin;

import com.example.addon.render.GelUuidCarrier;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(EntityRenderState.class)
public abstract class MixinEntityRenderStateGelUuid implements GelUuidCarrier {
    @Unique
    private UUID exampleAddon$gelUuid;

    @Override
    public UUID exampleAddon$getGelUuid() {
        return exampleAddon$gelUuid;
    }

    @Override
    public void exampleAddon$setGelUuid(UUID id) {
        this.exampleAddon$gelUuid = id;
    }
}
