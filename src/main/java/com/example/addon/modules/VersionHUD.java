package com.example.addon.modules;

import dev.boze.api.addon.AddonHudModule;
import dev.boze.api.option.ColorOption;
import dev.boze.api.render.ColorMaker;
import dev.boze.api.render.HudLine;
import dev.boze.api.render.HudText;
import net.fabricmc.loader.api.FabricLoader;
import java.util.List;

public class VersionHUD extends AddonHudModule {
    public static final VersionHUD INSTANCE = new VersionHUD();

    public final ColorOption textColor = new ColorOption(this, "Text Color", "Color of the version text.", ColorMaker.staticColor(255, 0, 255), 1.0f);

    private VersionHUD() {
        super("VersionHUD", "Renders the addon version.");
    }

    @Override
    public List<HudLine> getLines() {
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
        
        return List.of(HudLine.of(
            new HudText(version, textColor.getValue().color)
        ));
    }
}