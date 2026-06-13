package com.example.addon;

import com.example.addon.commands.PrintModuleCommand;
import com.example.addon.commands.PrintOptionsCommand;
import com.example.addon.modules.AntiPiston;
import com.example.addon.modules.ChestButtons;
import com.example.addon.modules.GifGUI;
import com.example.addon.modules.GifHUD;
import com.example.addon.modules.MusicHUD;
import com.example.addon.modules.PlayMusic;
import com.example.addon.modules.SelfWeb;
import com.example.addon.modules.SmoothMotion;
import com.example.addon.modules.VersionHUD;

import dev.boze.api.BozeInstance;
import dev.boze.api.addon.Addon;

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
        // Register commands - demonstrate command API
        dispatcher.registerCommand(PrintModuleCommand.INSTANCE);
        dispatcher.registerCommand(PrintOptionsCommand.INSTANCE);
        modules.add(ChestButtons.INSTANCE);
        modules.add(SmoothMotion.INSTANCE);
        modules.add(PlayMusic.INSTANCE);
        modules.add(MusicHUD.INSTANCE);
        modules.add(SelfWeb.INSTANCE);
        modules.add(AntiPiston.INSTANCE);
        modules.add(GifHUD.INSTANCE);
        modules.add(GifGUI.INSTANCE);
        modules.add(VersionHUD.INSTANCE);
        // Register client module extensions - demonstrate extension API
        extensions.add(new ExampleExtension());

        // Register package for event handler
        BozeInstance.INSTANCE.registerPackage("com.example.addon");

        return true;
    }
}
