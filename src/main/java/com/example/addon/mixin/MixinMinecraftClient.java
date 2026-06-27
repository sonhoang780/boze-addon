package com.example.addon.mixin;

import com.example.addon.ExampleAddon;
import com.example.addon.modules.ElytraFix;
import com.example.addon.modules.LoadingScreen;
import com.example.addon.screens.CustomLoadingScreen;
import com.example.addon.screens.SkiaHud;
import dev.boze.api.BozeInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraftClient {

    // Accumulator for fractional tick timing (timer hack support for ElytraFix hover).
    private float timerAccumulator = 0f;

    @Inject(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;instance:Lnet/minecraft/client/Minecraft;"))
    private void onInit$setInstance(GameConfig args, CallbackInfo ci) {
        BozeInstance.INSTANCE.registerAddon(new ExampleAddon());
    }

    /**
     * Throttles game ticks when ElytraFix hover timer is active (< 1.0).
     * Injects at HEAD of tick() and cancels it when the accumulator hasn't reached 1.0,
     * so only 1-in-N ticks actually execute — e.g. speed=0.1 → ~2 ticks/second.
     *
     * If this still crashes, open MinecraftClient in your IDE decompiler, find the
     * method that processes one game tick (called from the render loop), and replace
     * "tick" below with that method's Mojang-mapped name.
     */
    /**
     * End-of-frame hook for the persistent GPU-Skija HUD layer (SkiaHud), injected
     * right before the buffer swap so Skija draws on top of the final frame — the
     * Skimi technique. Drawing here (not during GUI extraction) is what makes
     * GPU-Skija safe and gives no-CPU-raster performance.
     */
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
    private void skiahud$onEndFrame(boolean tick, CallbackInfo ci) {
        SkiaHud.onEndFrame();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void throttleGameTick(CallbackInfo ci) {
        float speed = ElytraFix.hoverTimerSpeed;
        if (speed >= 1.0f) return;
        timerAccumulator += speed;
        if (timerAccumulator < 1.0f) {
            ci.cancel();
        } else {
            timerAccumulator -= 1.0f;
        }
    }

    /**
     * Intercepts the very first navigation to TitleScreen and redirects to
     * CustomLoadingScreen (which plays the intro video then opens CustomTitleScreen).
     * Subsequent openings (e.g. returning from a world) go straight to CustomTitleScreen.
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void interceptTitleScreen(Screen screen, CallbackInfo ci) {
        // A/B test switch: create <game-dir>/boze-addon/no_custom_menu.txt to fully
        // disable the custom menu (vanilla title screen, zero Skija/video from it).
        // Lets us isolate whether the custom menu — or the Skija 0.143.17 upgrade —
        // is what affects MusicHUD's TextBloomPlus text.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("boze-addon/no_custom_menu.txt").toFile().exists()) {
            return;
        }
        if (screen instanceof TitleScreen) {
            if (!LoadingScreen.INSTANCE.active) return;
            System.err.println("[BozeMenu] Intercepted TitleScreen (introPlayed=" + LoadingScreen.INSTANCE.introPlayed + ")");
            if (!LoadingScreen.INSTANCE.introPlayed) {
                LoadingScreen.INSTANCE.introPlayed = true;
                ((Minecraft)(Object)this).setScreen(new CustomLoadingScreen());
            } else {
                ((Minecraft)(Object)this).setScreen(
                    new com.example.addon.screens.CustomTitleScreen());
            }
            ci.cancel();
        }
    }
}
