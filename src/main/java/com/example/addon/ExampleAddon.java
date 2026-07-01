package com.example.addon;

import java.util.Map;

import com.example.addon.commands.ItemDropCommand;
import com.example.addon.commands.KitCommand;
import com.example.addon.commands.PrintModuleCommand;
import com.example.addon.commands.PrintOptionsCommand;
import com.example.addon.modules.AntiMace;
import com.example.addon.modules.BetterBasePlace;
import com.example.addon.modules.MaceAura;
import com.example.addon.modules.AntiPiston;
import com.example.addon.modules.Dummy;
import com.example.addon.modules.AutoPortal;
import com.example.addon.modules.betterrekit.EvilRekit;
import com.example.addon.modules.ChestButtons;
import com.example.addon.modules.BetterChams;
import com.example.addon.modules.EbookReader;
import com.example.addon.modules.EBounce;
import com.example.addon.modules.VanillaEBounce;
import com.example.addon.modules.EBouncePlus;
import com.example.addon.modules.ElytraFix;
import com.example.addon.modules.FakeFly;
import com.example.addon.modules.GifHUD;
import com.example.addon.modules.HUDEditor;
import com.example.addon.modules.InventoryCleaner;
import com.example.addon.modules.InventorySorter;
import com.example.addon.modules.InvMovePlus;
import com.example.addon.modules.LoadingScreen;
import com.example.addon.modules.MusicHUD;
import com.example.addon.modules.TungTungSahur;
import com.example.addon.modules.PlayMusic;
import com.example.addon.modules.SpotifyIntegration;
import com.example.addon.modules.SelfWeb;
import com.example.addon.modules.VersionHUD;
import dev.boze.api.addon.Addon;
import dev.boze.api.BozeInstance;

public class ExampleAddon extends Addon {

    public static final String ID = "1337";
    public static final String NAME = "SmoothCamera";
    public static final String DESCRIPTION = "SmoothCamera";
    public static final String VERSION = "1.0.0";

    public ExampleAddon() {
        super(ID, NAME, DESCRIPTION, VERSION);
    }

    @Override
    public boolean initialize() {
        AddonConfig.load();
        ConfigMigrator.migrate(ID, Map.of(
            "FakeFly", Map.of(
                "Speed",           "UpSpeed",
                "Vertical Speed",  "DownSpeed",
                "Firework Delay",  "ConserveDelay",
                "Auto Firework",   "AutoTakeoff",
                "Chestplate Mode", "ChestplateMode",
                "Swap Mode",       "Swap"
            )
        ));
        dispatcher.registerCommand(ItemDropCommand.INSTANCE);
        dispatcher.registerCommand(KitCommand.INSTANCE);
        dispatcher.registerCommand(PrintModuleCommand.INSTANCE);
        dispatcher.registerCommand(PrintOptionsCommand.INSTANCE);
        modules.add(AntiMace.INSTANCE);
        modules.add(BetterBasePlace.INSTANCE);
        modules.add(MaceAura.INSTANCE);
        modules.add(Dummy.INSTANCE);
        modules.add(AntiPiston.INSTANCE);
        modules.add(EBounce.INSTANCE);
        modules.add(VanillaEBounce.INSTANCE);
        modules.add(EBouncePlus.INSTANCE);
        modules.add(AutoPortal.INSTANCE);
        modules.add(ChestButtons.INSTANCE);
        BetterChams.registerTextures();
        modules.add(BetterChams.INSTANCE);
        modules.add(EbookReader.INSTANCE);
        modules.add(ElytraFix.INSTANCE);
        modules.add(FakeFly.INSTANCE);
        modules.add(EvilRekit.INSTANCE);
        modules.add(GifHUD.INSTANCE);
        modules.add(HUDEditor.INSTANCE);
        modules.add(InventoryCleaner.INSTANCE);
        modules.add(InventorySorter.INSTANCE);
        modules.add(LoadingScreen.INSTANCE);
        modules.add(InvMovePlus.INSTANCE);
        modules.add(MusicHUD.INSTANCE);
        modules.add(PlayMusic.INSTANCE);
        modules.add(SpotifyIntegration.INSTANCE);
        modules.add(SelfWeb.INSTANCE);
        modules.add(TungTungSahur.INSTANCE);
        modules.add(VersionHUD.INSTANCE);
        // Register client module extensions - demonstrate extension API
        extensions.add(new ExampleExtension());

        // Register package for event handler
        BozeInstance.INSTANCE.registerPackage("com.example.addon");

        return true;
    }
}
