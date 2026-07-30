package com.example.addon.mixin;

import com.example.addon.util.SilentGuiHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips extracting (queuing for render) a container screen entirely when the addon's own
 * "silent" toggles want it hidden -- BedAura's AutoCraft (CraftingMenu) or EvilRekit's
 * automated regear pipeline (EvilRekit.isAutoActive() -- covers EVERY container the
 * pipeline touches, not just the ShulkerBoxMenu itself: FIND_SHULKER/GRAB_SHULKER/
 * RETURN_SHULKER all operate on the ender chest's plain ChestMenu first). The screen/menu
 * themselves are completely untouched
 * (still open server-side, still ticking, still receiving click packets exactly as
 * before, same as EvilRekit's own well-tested container-click flow) -- this only stops the
 * panel from ever being drawn, so the player never sees the GUI pop up while it runs.
 * <p>
 * Known limitation: the mouse cursor is still released/captured by the (invisible) screen
 * for as long as it's open, same as any real container screen -- going further (keeping
 * full camera/movement control too) would need intercepting vanilla's screen-open packet
 * handling itself (ClientPacketListener.handleOpenScreen -> MenuScreens.create), not just
 * rendering; that path was checked via javap and doesn't expose a safe redirect point
 * without touching per-menu-type lambda internals, so it's out of scope here.
 */
@Mixin(AbstractContainerScreen.class)
public class MixinAbstractContainerScreen {

    /**
     * extractBackground runs BEFORE extractRenderState (see Screen#extractRenderStateWithTooltipAndSubtitles)
     * and draws the panel texture itself -- cancelling only extractRenderState left the background
     * (chest/shulker/crafting-table panel) visible. AbstractContainerScreen doesn't override
     * extractBackground itself (each concrete screen does its own), so the sibling injects live in
     * MixinContainerBackground on the concrete screen classes, sharing SilentGuiHelper.shouldHide.
     */
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void silentGui$suppress(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (SilentGuiHelper.shouldHide((Object) this)) ci.cancel();
    }
}
