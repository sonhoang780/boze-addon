package com.example.addon.modules;

import com.example.addon.screens.CustomTitleScreen;
import com.example.addon.video.VideoPlayer;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

import java.awt.Desktop;
import java.io.File;

public class LoadingScreen extends AddonModule {
    public static final LoadingScreen INSTANCE = new LoadingScreen();

    public final ToggleOption openBackground = new ToggleOption(this, "Background",
            "Open .minecraft/boze and import background.mp4", false);
    public final ToggleOption openIntro = new ToggleOption(this, "Intro",
            "Open .minecraft/boze and import intro.mp4", false);
    public final ToggleOption sound = new ToggleOption(this, "Sound",
            "Play audio from background video", true);

    public boolean active = false;
    // Set true after CustomLoadingScreen is shown; reset on re-enable so it replays.
    public boolean introPlayed = false;

    public LoadingScreen() {
        super("LoadingScreen", "Custom loading screen and main menu background video");
    }

    @Override
    public void onEnable() {
        active = true;
        introPlayed = false; // replay intro each time module is enabled
    }

    @Override
    public void onDisable() {
        active = false;
        // Stop background audio immediately.
        VideoPlayer bg = CustomTitleScreen.activeBgVideo;
        if (bg != null) bg.setAudioEnabled(false);
        // If user is currently on the custom title screen, redirect to vanilla.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.currentScreen instanceof CustomTitleScreen) {
            mc.execute(() -> mc.setScreen(new TitleScreen()));
        }
    }

    @EventHandler
    private void onTick(EventTick e) {
        if (!active) return;

        if (openBackground.getValue()) {
            openBozeFolder();
            openBackground.setValue(false);
        }
        if (openIntro.getValue()) {
            openBozeFolder();
            openIntro.setValue(false);
        }

        // Sync audio state to the currently playing background video.
        VideoPlayer bg = CustomTitleScreen.activeBgVideo;
        if (bg != null) bg.setAudioEnabled(sound.getValue());
    }

    private void openBozeFolder() {
        try {
            File bozeDir = FabricLoader.getInstance().getGameDir().resolve("boze").toFile();
            if (!bozeDir.exists()) bozeDir.mkdirs();
            Desktop.getDesktop().open(bozeDir);
        } catch (Exception e) {
            System.err.println("[LoadingScreen] Could not open boze folder: " + e);
        }
    }
}
