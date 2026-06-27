package com.example.addon.screens;

import com.example.addon.video.VideoPlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;

import java.io.File;

public class CustomLoadingScreen extends Screen {

    private static final long   FADE_DURATION_MS  = 900L;
    private static final long   SAFETY_TIMEOUT_MS = 15000L;
    private static final int    STREAM_BUFFER_MIN = 30;

    private VideoPlayer videoPlayer;
    private final FrameTexture videoTex = new FrameTexture("intro");
    private long lastFrameAdvMs = -1;

    private long    initMs          = -1;
    private boolean transitioning   = false;
    private long    transitionStartMs = -1;
    private boolean done            = false;

    public CustomLoadingScreen() {
        super(Component.empty());
    }

    @Override
    public void init() {
        initMs = System.currentTimeMillis();
        
        // GRAB DYNAMIC NAME FROM MODULE
        String targetIntroPath = "boze/intro/" + com.example.addon.modules.LoadingScreen.INSTANCE.selectedIntroName;
        File videoFile = FabricLoader.getInstance().getGameDir().resolve(targetIntroPath).toFile();
        
        if (!videoFile.exists()) {
            System.err.println("[BozeMenu] Intro video not found, skipping to title");
            openTitleScreen();
            return;
        }

        videoPlayer = new VideoPlayer("intro", false, 1920, 1080, 60, true);
        videoPlayer.startDecoding(videoFile, null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (done) return;

        context.fill(0, 0, width, height, 0xFF000000);

        long nowMs = System.currentTimeMillis();

        if (videoPlayer != null) {
            if (!videoPlayer.isPlaying()) {
                int  queued     = videoPlayer.getStreamQueueSize();
                boolean fatal   = (videoPlayer.hasError() && queued == 0)
                               || (videoPlayer.isDecodeFinished() && queued == 0)
                               || nowMs - initMs > SAFETY_TIMEOUT_MS;
                boolean ready   = queued >= STREAM_BUFFER_MIN
                               || (videoPlayer.isDecodeFinished() && queued > 0);
                if (fatal) {
                    System.err.println("[BozeMenu] intro skipped (error/empty/timeout)");
                    openTitleScreen();
                    return;
                }
                if (ready) {
                    videoPlayer.play();
                    lastFrameAdvMs = nowMs;
                }
                return;
            }

            long frameInterval = Math.max(15L, Math.round(1000.0 / videoPlayer.getVideoFps()));
            if (lastFrameAdvMs >= 0 && nowMs - lastFrameAdvMs >= frameInterval) {
                NativeImage frame = videoPlayer.pollStreamFrame();
                if (frame != null) {
                    videoTex.uploadNative(frame);
                    frame.close();
                    lastFrameAdvMs = nowMs;
                } else if (videoPlayer.isDecodeFinished() && !transitioning) {
                    transitioning     = true;
                    transitionStartMs = nowMs;
                }
            }
            if (videoTex.ready()) drawLetterboxed(context);

            if (!videoTex.ready() && nowMs - initMs > SAFETY_TIMEOUT_MS) {
                System.err.println("[BozeMenu] intro timeout while playing");
                openTitleScreen();
                return;
            }
        }

        if (transitioning) {
            long  elapsed = nowMs - transitionStartMs;
            float alpha   = Math.min(1.0f, (float) elapsed / FADE_DURATION_MS);
            context.fill(0, 0, width, height, ((int)(alpha * 255) & 0xFF) << 24);
            if (alpha >= 1.0f) openTitleScreen();
        }
    }

    private void drawLetterboxed(GuiGraphicsExtractor context) {
        int fw = videoTex.width(), fh = videoTex.height();
        if (fw <= 0 || fh <= 0) return;
        float scale = Math.min((float) width / fw, (float) height / fh);
        int dw = Math.round(fw * scale), dh = Math.round(fh * scale);
        int dx = (width - dw) / 2, dy = (height - dh) / 2;
        videoTex.blit(context, dx, dy, dw, dh);
    }

    private void openTitleScreen() {
        if (done) return;
        done = true;
        cleanup();
        minecraft.execute(() -> minecraft.setScreen(new CustomTitleScreen()));
    }

    private void cleanup() {
        if (videoPlayer != null) { videoPlayer.dispose(); videoPlayer = null; }
        videoTex.dispose();
        lastFrameAdvMs = -1;
    }

    @Override public boolean isPauseScreen()     { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void    removed()          { cleanup(); }
}