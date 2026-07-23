package com.example.addon;

import java.util.Map;

import com.example.addon.commands.HoodResearchCommand;
import com.example.addon.commands.ItemDropCommand;
import com.example.addon.commands.KitCommand;
import com.example.addon.commands.PrintModuleCommand;
import com.example.addon.commands.PrintOptionsCommand;
import com.example.addon.commands.SaveConfigCommand;
import com.example.addon.modules.AntiMace;
import com.example.addon.modules.AntiPiston;
import com.example.addon.modules.AuraStep;
import com.example.addon.modules.AutoPortal;
import com.example.addon.modules.AutoShop;
import com.example.addon.modules.AutoWalk;
import com.example.addon.modules.BetterBasePlace;
import com.example.addon.modules.BetterChams;
import com.example.addon.modules.ChestButtons;
import com.example.addon.modules.ControlRocket;
import com.example.addon.modules.CustomSky;
import com.example.addon.modules.Dummy;
import com.example.addon.modules.EBouncePlus;
import com.example.addon.modules.EbookReader;
import com.example.addon.modules.ElytraFix;
import com.example.addon.modules.FastWeb;
import com.example.addon.modules.GifHUD;
import com.example.addon.modules.HUDEditor;
import com.example.addon.modules.HoleSnap;
import com.example.addon.modules.InvMovePlus;
import com.example.addon.modules.InventoryCleaner;
import com.example.addon.modules.InventorySorter;
import com.example.addon.modules.KillEffect;
import com.example.addon.modules.StashArrange;
import com.example.addon.modules.LoadingScreen;
import com.example.addon.modules.BetterOffhand;
import com.example.addon.modules.MainHand;
import com.example.addon.modules.NoSlow;
import com.example.addon.modules.Velocity;
import com.example.addon.modules.MoreKnockback;
import com.example.addon.modules.PistonPush;
import com.example.addon.modules.PistonAura;
import com.example.addon.modules.IgnoreClimb;
import com.example.addon.modules.MusicHUD;
import com.example.addon.modules.PathFinder;
import com.example.addon.modules.Replenish;
import com.example.addon.modules.PlayMusic;
import com.example.addon.modules.SelfWeb;
import com.example.addon.modules.SpotifyIntegration;
import com.example.addon.modules.TargetESP;
import com.example.addon.modules.PenisESP;
import com.example.addon.modules.HoodResearch;
import com.example.addon.modules.Notification;
import com.example.addon.modules.TungTungSahur;
import com.example.addon.modules.VersionHUD;
import com.example.addon.modules.WebBrowser;
import com.example.addon.modules.betterrekit.EvilRekit;
import com.example.addon.modules.chestscan.ChestScan;
import com.example.addon.modules.stashfinder.StashFinder;

import dev.boze.api.BozeInstance;
import dev.boze.api.addon.Addon;

public class ExampleAddon extends Addon {

    public static final String ID = "1337";
    public static final String NAME = "BozeAddon";
    public static final String DESCRIPTION = "BozePlus";
    public static final String VERSION = "1.0.0";

    public ExampleAddon() {
        super(ID, NAME, DESCRIPTION, VERSION);
    }

    @Override
    public boolean initialize() {
        AddonConfig.load();
        ConfigMigrator.renameModule(ID, "FakeFly", "ControlRocket");
        ConfigMigrator.migrate(ID, Map.of(
            "ControlRocket", Map.of(
                "Speed",           "UpSpeed",
                "Vertical Speed",  "DownSpeed",
                "Firework Delay",  "ConserveDelay",
                "Auto Firework",   "AutoTakeoff",
                "Chestplate Mode", "ChestplateMode",
                "ChestplateMode",  "FakeFly",
                "Swap Mode",       "Swap"
            ),
            "WebBrowser", Map.of(
                "Open Key", "OpenKey"
            ),
            "BedAura", Map.of(
                "AutoCraft Bind", "AutoCraftBind"
            )
        ));
        dispatcher.registerCommand(ItemDropCommand.INSTANCE);
        dispatcher.registerCommand(KitCommand.INSTANCE);
        dispatcher.registerCommand(PrintModuleCommand.INSTANCE);
        dispatcher.registerCommand(PrintOptionsCommand.INSTANCE);
        dispatcher.registerCommand(com.example.addon.commands.StashFinderCommand.INSTANCE);
        dispatcher.registerCommand(HoodResearchCommand.INSTANCE);
        dispatcher.registerCommand(SaveConfigCommand.INSTANCE);
        dispatcher.registerCommand(com.example.addon.commands.SetBindCommand.INSTANCE);
        modules.add(AntiMace.INSTANCE);
        modules.add(AutoShop.INSTANCE);
        modules.add(MainHand.INSTANCE);
        modules.add(Replenish.INSTANCE);
        modules.add(BetterOffhand.INSTANCE);
        modules.add(Velocity.INSTANCE);
        modules.add(NoSlow.INSTANCE);
        modules.add(KillEffect.INSTANCE);
        modules.add(StashArrange.INSTANCE);
        modules.add(com.example.addon.modules.bedaura.BedAura.INSTANCE);
        modules.add(MoreKnockback.INSTANCE);
        modules.add(PistonPush.INSTANCE);
        modules.add(PistonAura.INSTANCE);
        modules.add(IgnoreClimb.INSTANCE);
        modules.add(BetterBasePlace.INSTANCE);
        modules.add(Dummy.INSTANCE);
        modules.add(AntiPiston.INSTANCE);
        // modules.add(EBounce.INSTANCE);
        // modules.add(VanillaEBounce.INSTANCE);
        modules.add(EBouncePlus.INSTANCE);
        modules.add(AutoPortal.INSTANCE);
        modules.add(AutoWalk.INSTANCE);
        modules.add(ChestButtons.INSTANCE);
        BetterChams.registerTextures();
        com.example.addon.render.GlowBlur.registerTextures();
        modules.add(BetterChams.INSTANCE);
        AuraStep.registerTextures();
        modules.add(AuraStep.INSTANCE);
        modules.add(com.example.addon.modules.Trails.INSTANCE);
        modules.add(com.example.addon.modules.Bubble.INSTANCE);
        TungTungSahur.registerTextures();
        CustomSky.registerTextures();
        modules.add(EbookReader.INSTANCE);
        modules.add(FastWeb.INSTANCE);
        modules.add(ElytraFix.INSTANCE);
        modules.add(ControlRocket.INSTANCE);
        modules.add(EvilRekit.INSTANCE);
        modules.add(GifHUD.INSTANCE);
        modules.add(HUDEditor.INSTANCE);
        modules.add(HoleSnap.INSTANCE);
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
        modules.add(PathFinder.INSTANCE);
        modules.add(ChestScan.INSTANCE);
        modules.add(StashFinder.INSTANCE);
        modules.add(CustomSky.INSTANCE);
        modules.add(WebBrowser.INSTANCE);
        modules.add(TargetESP.INSTANCE);
        modules.add(PenisESP.INSTANCE);
        modules.add(HoodResearch.INSTANCE);
        modules.add(Notification.INSTANCE);

        // Must run after every modules.add() above (needs the full list) and before
        // Boze's own Addon#load() reads config.json — backfills any option missing from
        // the saved file so Boze's loader never NPEs on a newly-added option and silently
        // drops every module after it in load order. See ConfigMigrator.fillMissingOptions.
        ConfigMigrator.fillMissingOptions(ID, modules);

        // Register client module extensions - demonstrate extension API
        extensions.add(new ExampleExtension());

        // Register package for event handler
        BozeInstance.INSTANCE.registerPackage("com.example.addon");

        // Always-on listener (not a toggleable module) -- see LiveModeCache's doc.
        BozeInstance.INSTANCE.subscribe(com.example.addon.util.LiveModeCache.INSTANCE);

        return true;
    }
}
