package com.example.addon.mixin;

import com.example.addon.modules.GifGUI;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DrawContext.class)
public abstract class DrawContextMixin {
    
    // Mai phục lúc Boze bật kéo cắt (Scissor) để giới hạn vùng cuộn của Panel
    @Inject(method = "enableScissor", at = @At("HEAD"))
    private void onEnableScissor(int x1, int y1, int x2, int y2, CallbackInfo ci) {
        if (GifGUI.INSTANCE != null && GifGUI.INSTANCE.active) {
            GifGUI.INSTANCE.addScissorBound(x1, y1, x2, y2);
        }
    }
}