package com.example.addon.mixin;

import com.example.addon.util.SilentGuiHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sibling of MixinAbstractContainerScreen: extractBackground draws the panel texture and each
 * concrete screen overrides it directly (not inherited from AbstractContainerScreen), so it can't
 * be caught by a single mixin on the common superclass. Shares MixinAbstractContainerScreen's
 * shouldHide() so both injects agree on when to suppress.
 */
@Mixin({ContainerScreen.class, ShulkerBoxScreen.class, CraftingScreen.class})
public class MixinContainerBackground {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void silentGui$suppressBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (SilentGuiHelper.shouldHide((Object) this)) ci.cancel();
    }
}
