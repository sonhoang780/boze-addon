package com.example.addon.mixin;

import com.example.addon.modules.GameAnimation;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Ported from TrollHack's GameAnimation.hotbarSelectionX: eases the hotbar selection outline
 * sprite toward the newly-selected slot instead of snapping instantly.
 *
 * Target found via javap -c on Gui.class (26.1.2, no source available): extractItemHotbar
 * draws HOTBAR_SPRITE (background) then HOTBAR_SELECTION_SPRITE (the outline) via two
 * consecutive GuiGraphicsExtractor.blitSprite(pipeline, sprite, x, y, w, h) calls -- the
 * selection outline is the SECOND one (ordinal = 1), its x arg (index 2) computed as
 * guiWidth/2 - 91 - 1 + getSelectedSlot() * 20.
 */
@Mixin(Gui.class)
public abstract class MixinGuiHotbar {

    @ModifyArg(
        method = "extractItemHotbar",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
            ordinal = 1
        ),
        index = 2
    )
    private int gameAnimation$smoothHotbarSelectionX(int vanillaX) {
        return Math.round(GameAnimation.INSTANCE.track("hotbar_selection_x", (float) vanillaX));
    }
}
