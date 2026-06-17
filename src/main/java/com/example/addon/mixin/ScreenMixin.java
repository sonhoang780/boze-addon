package com.example.addon.mixin;

import com.example.addon.modules.GifGUI;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenMixin {

    // QUAN TRỌNG: tiêm vào renderWithTooltip CHỨ KHÔNG phải render.
    //
    // Vòng lặp game gọi screen.renderWithTooltip(...) cho MỌI Screen, và phương
    // thức này được định nghĩa ở chính lớp Screen. Các GUI tự chế (như ClickGUI
    // của Boze) thường override render() và KHÔNG gọi super.render() — nên mixin
    // ở HEAD của render() sẽ không bao giờ chạy cho màn hình đó (đây chính là lý
    // do bản cũ "không hoạt động"). renderWithTooltip thì hầu như không ai override,
    // nên HEAD ở đây chạy chắc chắn cho mọi màn hình, TRƯỚC khi screen vẽ bất cứ gì.
    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void gifgui$onRenderHead(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!GifGUI.INSTANCE.active) return;
        Screen self = (Screen) (Object) this;
        // Toàn bộ logic lọc màn hình + vẽ nằm trong GifGUI cho dễ chỉnh.
        GifGUI.INSTANCE.onScreenRender(self, context);
    }
}