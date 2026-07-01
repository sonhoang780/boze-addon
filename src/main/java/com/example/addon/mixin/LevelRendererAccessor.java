package com.example.addon.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("entityOutlineTarget")
    RenderTarget getEntityOutlineTarget();

    @Invoker("initOutline")
    void invokeInitOutline();
}
