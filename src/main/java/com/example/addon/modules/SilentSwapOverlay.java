package com.example.addon.modules;

import com.example.addon.util.SilentSwapTracker;
import dev.boze.api.addon.AddonModule;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * [Feature Request] Silent Swap Overlay (GitHub issue #2): shows what item you're currently
 * silent-swapped to. Only MainHand and PhobosDoubleHand hold a silent swap open-ended (their
 * "silentHeld" state, see SilentSwapTracker) -- every other module's SwapType.Silent use is a
 * single-tick swap+swapBack around one action, gone again before the next frame renders, so
 * there'd be nothing stable to show for those.
 */
public class SilentSwapOverlay extends AddonModule {
    public static final SilentSwapOverlay INSTANCE = new SilentSwapOverlay();

    private boolean active = false;

    private double posX = com.example.addon.util.HudPositions.getX("SilentSwapOverlay", 4.0);
    private double posY = com.example.addon.util.HudPositions.getY("SilentSwapOverlay", 4.0);

    private SilentSwapOverlay() {
        super("SilentSwapOverlay", "Shows the item currently held via silent swap (MainHand/PhobosDoubleHand).");
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("example-addon", "silentswapoverlay"), (context, tracker) -> {
            if (this.active) render(context);
        });
    }

    @Override
    public void onEnable() { active = true; }

    @Override
    public void onDisable() { active = false; }

    private void render(GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (!SilentSwapTracker.isActive()) return;

        ItemStack item = SilentSwapTracker.item();
        String label = "§7[Silent] §f" + SilentSwapTracker.moduleName() + ": " + item.getHoverName().getString();
        context.text(mc.font, label, (int) posX, (int) posY, 0xFFFFFFFF, true);
    }
}
