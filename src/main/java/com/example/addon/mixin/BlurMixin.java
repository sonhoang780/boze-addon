package com.example.addon.mixin;

import com.example.addon.shader.impl.KawaseBlur;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Port trực tiếp từ SoarClient MixinGameRenderer.java
 *
 * Inject BEFORE InGameHud.render() — đây là thời điểm đúng vì:
 *  1. World đã render xong → main framebuffer có ảnh game hoàn chỉnh
 *  2. HUD chưa vẽ → blur không bị artifact từ text/UI
 *  3. Framebuffer đang ở trạng thái valid → không có GL_INVALID_FRAMEBUFFER_OPERATION
 *
 * Đây là lý do SoarClient KHÔNG dùng PostEffectProcessor hay FrameGraphBuilder —
 * họ inject đúng điểm trong render pipeline nơi GL state đã sẵn sàng,
 * và dùng LWJGL trực tiếp qua GlStateManager để không bị layer abstraction của Mojang.
 */
@Mixin(GameRenderer.class)
public class BlurMixin {

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            shift = At.Shift.BEFORE
        )
    )
    private void musichud_beforeHud(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        // Chỉ chạy khi MusicHUD đang active và yêu cầu blur
        if (!com.example.addon.utils.BlurUtils.blurRequested) return;
        KawaseBlur.MUSIC_HUD_BLUR.draw(8); // radius 8 = mức blur mượt, giống SoarClient default
    }
}
