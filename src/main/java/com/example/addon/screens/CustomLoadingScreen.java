package com.example.addon.screens;

import com.example.addon.video.VideoPlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.text.Text;

import java.io.File;

/**
 * Full-screen loading screen that plays the intro video once, then fades to
 * black and opens CustomTitleScreen. Frames are decoded off the render thread
 * via streaming VideoPlayer and uploaded via FrameTexture; everything draws
 * through DrawContext (no live-framebuffer Skija).
 *
 * Playback only starts after STREAM_BUFFER_MIN frames are queued so the clock
 * never outruns decode (prevents the "stuttering from the start" symptom).
 *
 * Video file: <game-dir>/boze/intro.mp4
 */
public class CustomLoadingScreen extends Screen {

    private static final String VIDEO_PATH    = "boze/intro.mp4";
    private static final long   FADE_DURATION_MS  = 900L;
    private static final long   SAFETY_TIMEOUT_MS = 15000L;
    // Frames to buffer before starting playback: ~0.5 s at 60 fps.
    private static final int    STREAM_BUFFER_MIN = 30;

    private VideoPlayer videoPlayer;
    private final FrameTexture videoTex = new FrameTexture("intro");
    private long lastFrameAdvMs = -1;

    private long    initMs          = -1;
    private boolean transitioning   = false;
    private long    transitionStartMs = -1;
    private boolean done            = false;

    public CustomLoadingScreen() {
        super(Text.empty());
    }

    @Override
    public void init() {
        initMs = System.currentTimeMillis();
        File videoFile = FabricLoader.getInstance().getGameDir().resolve(VIDEO_PATH).toFile();
        if (!videoFile.exists()) {
            System.err.println("[BozeMenu] intro.mp4 not found, skipping to title");
            openTitleScreen();
            return;
        }
        // Streaming at 1920×1080, 60-frame queue.
        // play() is NOT called here — render() waits for STREAM_BUFFER_MIN frames first
        // so the queue is never empty at the start of playback.
        videoPlayer = new VideoPlayer("intro", false, 1920, 1080, 60, true);
        videoPlayer.startDecoding(videoFile, null);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (done) return;

        context.fill(0, 0, width, height, 0xFF000000);

        long nowMs = System.currentTimeMillis();

        if (videoPlayer != null) {
            if (!videoPlayer.isPlaying()) {
                // ── Pre-buffering phase ──────────────────────────────────────
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
                // Still buffering — show black screen and return;
                // don't attempt to render the transitioning overlay yet either.
                return;
            }

            // ── Playback phase ───────────────────────────────────────────────
            long frameInterval = Math.max(15L, Math.round(1000.0 / videoPlayer.getVideoFps()));
            if (lastFrameAdvMs >= 0 && nowMs - lastFrameAdvMs >= frameInterval) {
                NativeImage frame = videoPlayer.pollStreamFrame();
                if (frame != null) {
                    videoTex.uploadNative(frame);
                    frame.close();
                    lastFrameAdvMs = nowMs;
                } else if (videoPlayer.isDecodeFinished() && !transitioning) {
                    // Queue exhausted + decode finished → video ended.
                    transitioning     = true;
                    transitionStartMs = nowMs;
                }
            }
            if (videoTex.ready()) drawLetterboxed(context);

            // Safety: playing but nothing shown and timed out.
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

    private void drawLetterboxed(DrawContext context) {
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
        client.execute(() -> client.setScreen(new CustomTitleScreen()));
    }

    private void cleanup() {
        if (videoPlayer != null) { videoPlayer.dispose(); videoPlayer = null; }
        videoTex.dispose();
        lastFrameAdvMs = -1;
    }

    @Override public boolean shouldPause()      { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void    removed()          { cleanup(); }
}
