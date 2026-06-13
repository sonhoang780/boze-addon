package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ColorOption;
import dev.boze.api.render.ColorMaker;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public class VersionHUD extends AddonModule {
    public static final VersionHUD INSTANCE = new VersionHUD();
    public boolean active = false;

    public final SliderOption posX = new SliderOption(this, "X Position", "Horizontal position.", 100.0, 0.0, 2000.0, 1.0);
    public final SliderOption posY = new SliderOption(this, "Y Position", "Vertical position.", 100.0, 0.0, 1000.0, 1.0);
    
    // FIX CHÍ MẠNG: Thêm 1.0f (Alpha) vào cuối tham số của ColorOption
    public final ColorOption textColor = new ColorOption(this, "Text Color", "Color of the version text.", ColorMaker.staticColor(255, 0, 255), 1.0f);

    private VersionHUD() {
        super("VersionHUD", "Renders the addon version using Boze Color API.");
        
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            if (this.active) render(context);
        });
    }

    @Override public void onEnable() { this.active = true; }
    @Override public void onDisable() { this.active = false; }

    private void render(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;

        String version = FabricLoader.getInstance().getModContainer("boze-addon")
                         .map(c -> c.getMetadata().getVersion().getFriendlyString())
                         .orElse("Unknown");

        if (version.equals("Unknown")) {
            version = FabricLoader.getInstance().getAllMods().stream()
                .filter(mod -> mod.getMetadata().getVersion().getFriendlyString().contains("boze-addon"))
                .findFirst()
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("Unknown");
        }
        
        String text = version;
        
        int colorInt = textColor.getValue().color.getPacked(); // Dùng getPacked() để xuất số nguyên RGB
        
        context.drawText(mc.textRenderer, text, (int)(double)posX.getValue(), (int)(double)posY.getValue(), colorInt, true);
    }
}