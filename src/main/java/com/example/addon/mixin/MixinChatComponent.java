package com.example.addon.mixin;

import com.example.addon.screens.WebBrowserScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses chat rendering while WebBrowserScreen is open. Hooks ChatComponent's own
 * extractRenderState directly (the actual paint call, verified via javap against
 * ChatComponent.class in the 26.1.2 merged jar -- overload disambiguated by descriptor)
 * instead of Gui#extractChat, which only forwards to it and isn't guaranteed to be the
 * only caller.
 */
@Mixin(ChatComponent.class)
public abstract class MixinChatComponent {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void webbrowser$suppressOverWebBrowserScreen(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY,
                                                           int tickCount, ChatComponent.DisplayMode displayMode, boolean focused,
                                                           CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof WebBrowserScreen) {
            ci.cancel();
        }
    }
}
