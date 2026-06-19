package com.example.addon.screens;

import com.example.addon.video.VideoPlayer;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.Rect;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.text.Text;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Custom main-menu screen. Layout: date + big time (top) → "hello" (script font,
 * white neon, left-to-right writing animation) → horizontal menu row.
 *
 * Performance: everything is uploaded as a texture and drawn via DrawContext (no
 * live-framebuffer Skija → Sodium-safe). Background video frames are pre-decoded
 * off-thread. The "hello" writing animation is a FLIPBOOK: each reveal step is
 * rasterised once (lazily, a couple per render call, kept ahead of playback) so
 * steady-state rendering is just texture blits → 144 fps. Date/time is a separate
 * texture re-rasterised only when the clock-second changes. All Skija text/paths
 * are rendered at framebuffer resolution (×scaleFactor) for crisp, non-pixelated
 * output.
 *
 * Background video: <game-dir>/boze/background.mp4
 */
public class CustomTitleScreen extends Screen {

    private static final String BG_VIDEO = "boze/background.mp4";
    private static final long HELLO_ANIM_MS = 2600L;
    private static final long HELLO_FADE_MS = 1500L; // fade-out after writing completes
    private static final int HELLO_FRAMES = 40;   // flipbook step count

    // ── Background video ────────────────────────────────────────────────────
    private VideoPlayer bgVideo;
    private final FrameTexture bgTex = new FrameTexture("bg");
    private int lastBgIdx = -1;
    private long lastBgFrameMs = -1;

    // ── Skija fonts ─────────────────────────────────────────────────────────
    private Font helloFont, dateFont, timeFont;
    private float helloWidth = 0f;
    private Path helloPath;
    private float[] contourLens;
    private float helloTotalLen = 0f;

    // ── "hello" flipbook ──────────────────────────────────────────────────────
    private static volatile boolean helloEverShown = false; // once per JVM session
    private final List<NativeImage> helloFrames = new ArrayList<>(); // guarded by (helloFrames)
    private Thread helloBuildThread = null;
    private volatile boolean helloBuildCancelled = false;
    private long helloFinishedMs  = -1; // when reveal=1.0 first reached
    private long helloBuildDoneMs = -1; // when all flipbook frames became ready
    private final FrameTexture helloTex = new FrameTexture("hello");
    private int lastHelloIdx = -1;
    private float ss = 2f;                       // supersample factor (framebuffer res)
    private int helloImgWGui, helloImgHGui;      // GUI dims of the hello image
    private int helloBaselineInImg;              // baseline y within the image (GUI)

    // ── Date / time texture ───────────────────────────────────────────────────
    private final FrameTexture dateTex = new FrameTexture("datetime");
    private String lastClock = "";
    private int dateImgWGui, dateImgHGui;

    private long initMs = -1;
    private long lastRenderMs = -1;

    // ── Menu ──────────────────────────────────────────────────────────────────
    private record MenuItem(String label, Runnable action) {}
    private final List<MenuItem> menu = new ArrayList<>();
    private final List<int[]> menuRects = new ArrayList<>(); // {x, y, w, h}
    private float[] hoverAmt = new float[0];

    public CustomTitleScreen() {
        super(Text.empty());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void init() {
        initMs      = System.currentTimeMillis();
        lastRenderMs = initMs;
        menu.clear();
        lastClock         = "";
        helloFinishedMs   = -1;
        helloBuildDoneMs  = -1;
        lastBgFrameMs     = -1;

        // Cancel any prior hello build thread before clearing frames.
        helloBuildCancelled = true;
        if (helloBuildThread != null) {
            try { helloBuildThread.join(200L); } catch (InterruptedException ignored) {}
            helloBuildThread = null;
        }
        clearHelloFrames();
        lastHelloIdx = -1;

        MinecraftClient mc = client;
        ss = (float) mc.getWindow().getScaleFactor();
        if (ss < 1f) ss = 1f;

        // Streaming background video: 854×480, 180-frame queue (~6s ahead at 30fps).
        // Re-creates each visit so the loop restarts from the beginning.
        if (bgVideo == null) {
            File f = FabricLoader.getInstance().getGameDir().resolve(BG_VIDEO).toFile();
            if (f.exists()) {
                bgVideo = new VideoPlayer("bg", true, 1920, 1080, 60, true);
                bgVideo.startDecoding(f, null);
                bgVideo.play(); // streaming doesn't need pre-buffering
            }
        }

        initFonts();

        helloImgWGui      = (int) Math.ceil(helloWidth) + 90;
        helloImgHGui      = 210;
        helloBaselineInImg = 150;

        dateImgWGui = width;
        dateImgHGui = (int)(height * 0.22f);

        menu.add(new MenuItem("Singleplayer", () -> mc.setScreen(new SelectWorldScreen(this))));
        menu.add(new MenuItem("Multiplayer",  () -> mc.setScreen(new MultiplayerScreen(this))));
        menu.add(new MenuItem("Options",       this::openOptions));
        menu.add(new MenuItem("Exit",          mc::scheduleStop));
        hoverAmt = new float[menu.size()];
        layoutMenu();

        // Build flipbook on a background thread (only once per JVM session; skipped on return visits).
        if (!helloEverShown && helloPath != null) {
            helloBuildCancelled = false;
            helloBuildThread = new Thread(() -> {
                for (int i = 0; i < HELLO_FRAMES && !helloBuildCancelled; i++) {
                    float r = HELLO_FRAMES == 1 ? 1f : (float) i / (HELLO_FRAMES - 1);
                    NativeImage ni = renderHelloFrame(r);
                    if (ni == null || helloBuildCancelled) {
                        if (ni != null) ni.close();
                        break;
                    }
                    synchronized (helloFrames) { helloFrames.add(ni); }
                }
            }, "HelloFrameBuilder");
            helloBuildThread.setDaemon(true);
            helloBuildThread.start();
        }
    }

    private void initFonts() {
        closeFonts();
        try {
            Typeface helloTf = matchFirst(new String[]{"Segoe Script", "Bradley Hand ITC", "Ink Free", "Comic Sans MS"}, FontStyle.ITALIC);
            helloFont = new Font(helloTf, 120f);
            helloFont.setEdging(FontEdging.ANTI_ALIAS);
            helloWidth = helloFont.measureTextWidth("hello");
            buildHelloPath();

            Typeface lightTf = matchFirst(new String[]{"Segoe UI Light", "Segoe UI", "Arial"}, FontStyle.NORMAL);
            dateFont = new Font(lightTf, 22f);
            timeFont = new Font(lightTf, 62f);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Typeface matchFirst(String[] families, FontStyle style) {
        for (String fam : families) {
            Typeface tf = FontMgr.getDefault().matchFamilyStyle(fam, style);
            if (tf != null) return tf;
        }
        return FontMgr.getDefault().matchFamilyStyle(null, style);
    }

    private void buildHelloPath() {
        helloPath = null; contourLens = null; helloTotalLen = 0f;
        if (helloFont == null) return;
        try {
            short[] glyphs = helloFont.getStringGlyphs("hello");
            float[] xpos = helloFont.getXPositions(glyphs, 0f);
            Path[] paths = helloFont.getPaths(glyphs);
            PathBuilder pb = new PathBuilder();
            for (int i = 0; i < glyphs.length; i++) {
                if (paths[i] != null) pb.addPath(paths[i], xpos[i], 0f);
            }
            helloPath = pb.build();

            List<Float> lens = new ArrayList<>();
            PathMeasure pm = new PathMeasure(helloPath, false);
            do {
                float l = pm.getLength();
                if (l > 0) { lens.add(l); helloTotalLen += l; }
            } while (pm.nextContour());
            contourLens = new float[lens.size()];
            for (int i = 0; i < lens.size(); i++) contourLens[i] = lens.get(i);
        } catch (Exception e) {
            e.printStackTrace();
            helloPath = null;
        }
    }

    private void layoutMenu() {
        int gap = 38;
        int totalW = 0;
        int[] widths = new int[menu.size()];
        for (int i = 0; i < menu.size(); i++) {
            widths[i] = client.textRenderer.getWidth(menu.get(i).label());
            totalW += widths[i];
        }
        totalW += gap * (menu.size() - 1);
        int x = (width - totalW) / 2;
        int y = (int)(height * 0.70f);
        int h = client.textRenderer.fontHeight;
        menuRects.clear();
        for (int i = 0; i < menu.size(); i++) {
            menuRects.add(new int[]{x, y - 4, widths[i], h + 8});
            x += widths[i] + gap;
        }
    }

    private void openOptions() {
        try {
            client.setScreen(new net.minecraft.client.gui.screen.option.OptionsScreen(this, client.options));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Render
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long nowMs = System.currentTimeMillis();
        float dt = Math.min(0.1f, (nowMs - lastRenderMs) / 1000f);
        lastRenderMs = nowMs;

        // 1. Black base.
        context.fill(0, 0, width, height, 0xFF000000);

        // 2. Background video (streaming, crop-to-fill).
        if (bgVideo != null && bgVideo.isPlaying()) {
            long frameInterval = Math.max(16L, Math.round(1000.0 / bgVideo.getVideoFps()));
            if (lastBgFrameMs < 0 || nowMs - lastBgFrameMs >= frameInterval) {
                NativeImage frame = bgVideo.pollStreamFrame();
                if (frame != null) {
                    bgTex.uploadNative(frame);
                    frame.close(); // pixel data copied into bgTex — safe to free
                    lastBgFrameMs = nowMs;
                }
            }
            if (bgTex.ready()) drawBgCropFill(context);
        }

        // 3. Vignette.
        context.fill(0, 0, width, height, 0x55000000);

        // 4. Date / time (sharp, separate texture, updates per second).
        updateDateTime(nowMs);
        if (dateTex.ready()) {
            int dx = (width - dateImgWGui) / 2;
            int dy = (int)(height * 0.10f);
            dateTex.blit(context, dx, dy, dateImgWGui, dateImgHGui);
        }

        // 5. "hello" flipbook (once per session, then fades out).
        drawHello(context, nowMs);

        // 6. Menu (parallax hover).
        drawMenu(context, mouseX, mouseY, dt);
    }

    private void drawBgCropFill(DrawContext context) {
        int fw = bgTex.width(), fh = bgTex.height();
        if (fw <= 0 || fh <= 0) return;
        float scale = Math.max((float) width / fw, (float) height / fh);
        int dw = Math.round(fw * scale), dh = Math.round(fh * scale);
        int dx = (width - dw) / 2, dy = (height - dh) / 2;
        bgTex.blit(context, dx, dy, dw, dh);
    }

    // ─── "hello" ───────────────────────────────────────────────────────────────

    private void drawHello(DrawContext context, long nowMs) {
        if (helloPath == null || helloEverShown) return;

        // Don't start until ALL flipbook frames are built. Starting the animation clock
        // before the build thread is done caused it to clamp to the last available frame
        // (which jumped backwards when new frames arrived) → stutter + lag.
        int available;
        synchronized (helloFrames) { available = helloFrames.size(); }
        if (available < HELLO_FRAMES) return;
        if (helloBuildDoneMs < 0) helloBuildDoneMs = nowMs; // first tick: all frames ready

        float reveal = Math.min(1.0f, (float)(nowMs - helloBuildDoneMs) / HELLO_ANIM_MS);
        int idx      = Math.round(reveal * (HELLO_FRAMES - 1));

        if (idx != lastHelloIdx) {
            NativeImage frame;
            synchronized (helloFrames) { frame = helloFrames.get(idx); }
            helloTex.uploadNative(frame);
            lastHelloIdx = idx;
        }
        if (!helloTex.ready()) return;

        int dx = (width  - helloImgWGui) / 2;
        int dy = (int)(height * 0.56f) - helloBaselineInImg;

        float alpha = 1.0f;
        if (reveal >= 1.0f) {
            if (helloFinishedMs < 0) helloFinishedMs = nowMs;
            float age = Math.min(1.0f, (float)(nowMs - helloFinishedMs) / HELLO_FADE_MS);
            if (age >= 1.0f) {
                helloEverShown = true;
                return;
            }
            alpha = 1.0f - age; // fade the texture itself, no black overlay
        }
        helloTex.blit(context, dx, dy, helloImgWGui, helloImgHGui, alpha);
    }

    /** Rasterises one "hello" reveal step at framebuffer resolution → NativeImage. */
    private NativeImage renderHelloFrame(float reveal) {
        int w = Math.round(helloImgWGui * ss);
        int h = Math.round(helloImgHGui * ss);
        Path revealed = buildRevealedPath(reveal);
        if (revealed == null) return null;
        try {
            return SkijaOverlay.render(w, h, canvas -> {
                canvas.scale(ss, ss);
                canvas.translate(45f, helloBaselineInImg);
                try (Paint glow = new Paint()) {
                    glow.setMode(PaintMode.STROKE);
                    glow.setStrokeWidth(9f);
                    glow.setStrokeCap(PaintStrokeCap.ROUND);
                    glow.setStrokeJoin(PaintStrokeJoin.ROUND);
                    glow.setColor(0x55FFFFFF);
                    glow.setMaskFilter(MaskFilter.makeBlur(FilterBlurMode.NORMAL, 12f));
                    canvas.drawPath(revealed, glow);
                }
                try (Paint line = new Paint()) {
                    line.setMode(PaintMode.STROKE);
                    line.setStrokeWidth(3.0f);
                    line.setStrokeCap(PaintStrokeCap.ROUND);
                    line.setStrokeJoin(PaintStrokeJoin.ROUND);
                    line.setColor(0xFFFFFFFF);
                    canvas.drawPath(revealed, line);
                }
            });
        } finally {
            revealed.close();
        }
    }

    private Path buildRevealedPath(float progress) {
        if (progress >= 1.0f) return new PathBuilder(helloPath).build();
        if (contourLens == null) return new PathBuilder(helloPath).build();
        float target = progress * helloTotalLen;
        PathMeasure pm = new PathMeasure(helloPath, false);
        PathBuilder out = new PathBuilder();
        int i = 0;
        do {
            if (i >= contourLens.length) break;
            float l = contourLens[i++];
            if (target <= 0.5f) break;
            float take = Math.min(l, target);
            pm.getSegment(0, take, out, true);
            target -= take;
            if (take < l - 0.5f) break;
        } while (pm.nextContour());
        return out.build();
    }

    // ─── date / time ─────────────────────────────────────────────────────────

    private void updateDateTime(long nowMs) {
        if (dateFont == null || timeFont == null) return;
        LocalDateTime now = LocalDateTime.now();
        String dateStr = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH));
        String timeStr = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String clock = dateStr + "|" + timeStr;
        if (clock.equals(lastClock) && dateTex.ready()) return;
        lastClock = clock;

        int w = Math.round(dateImgWGui * ss);
        int h = Math.round(dateImgHGui * ss);
        NativeImage ni = SkijaOverlay.render(w, h, canvas -> {
            canvas.scale(ss, ss);
            float dW = dateFont.measureTextWidth(dateStr);
            drawGlowText(canvas, dateStr, (dateImgWGui - dW) / 2f, dateImgHGui * 0.30f, dateFont, 0x44FFFFFF, 0xCCFFFFFF, 8f);
            float tW = timeFont.measureTextWidth(timeStr);
            drawGlowText(canvas, timeStr, (dateImgWGui - tW) / 2f, dateImgHGui * 0.85f, timeFont, 0x55FFFFFF, 0xF2FFFFFF, 16f);
        });
        if (ni != null) { dateTex.uploadNative(ni); ni.close(); }
    }

    private void drawGlowText(Canvas canvas, String s, float x, float y, Font font,
                              int glowColor, int color, float blur) {
        try (Paint glow = new Paint()) {
            glow.setColor(glowColor);
            glow.setMaskFilter(MaskFilter.makeBlur(FilterBlurMode.OUTER, blur));
            canvas.drawString(s, x, y, font, glow);
        }
        try (Paint p = new Paint()) {
            p.setColor(color);
            canvas.drawString(s, x, y, font, p);
        }
    }

    // ─── menu (parallax hover) ─────────────────────────────────────────────────

    private void drawMenu(DrawContext context, int mouseX, int mouseY, float dt) {
        for (int i = 0; i < menu.size(); i++) {
            int[] r = menuRects.get(i);
            boolean hovered = mouseX >= r[0] && mouseX < r[0] + r[2]
                           && mouseY >= r[1] && mouseY < r[1] + r[3];
            float target = hovered ? 1f : 0f;
            hoverAmt[i] += (target - hoverAmt[i]) * Math.min(1f, dt * 12f);
            float amt = hoverAmt[i];

            String label = menu.get(i).label();
            int tw = client.textRenderer.getWidth(label);
            float cx = r[0] + r[2] / 2f;
            float cy = r[1] + r[3] / 2f - 7f * amt;       // lift on hover
            float scale = 1f + 0.22f * amt;                // grow on hover

            int col = lerpColor(0xB0FFFFFF, 0xFFFFFFFF, amt);

            context.getMatrices().pushMatrix();
            context.getMatrices().translate(cx, cy);
            context.getMatrices().scale(scale, scale);
            context.drawText(client.textRenderer, label,
                -tw / 2, -client.textRenderer.fontHeight / 2, col, true);
            context.getMatrices().popMatrix();

            if (amt > 0.02f) {
                int uw = (int)(tw * scale);
                int ux = (int)(cx - uw / 2f);
                int uy = (int)(cy + client.textRenderer.fontHeight * 0.6f * scale);
                int a = (int)(amt * 255) & 0xFF;
                context.fill(ux, uy, ux + uw, uy + 1, (a << 24) | 0xFFFFFF);
            }
        }
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int)(ar + (br - ar) * t);
        int g = (int)(ag + (bg - ag) * t);
        int bl = (int)(ab + (bb - ab) * t);
        int al = (int)(aa + (ba - aa) * t);
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Input
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            double mx = click.x(), my = click.y();
            for (int i = 0; i < menu.size(); i++) {
                int[] r = menuRects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    menu.get(i).action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Cleanup
    // ═════════════════════════════════════════════════════════════════════════

    private void clearHelloFrames() {
        synchronized (helloFrames) {
            for (NativeImage ni : helloFrames) { try { ni.close(); } catch (Exception ignored) {} }
            helloFrames.clear();
        }
    }

    private void closeFonts() {
        if (helloFont != null) { helloFont.close(); helloFont = null; }
        if (dateFont  != null) { dateFont.close();  dateFont  = null; }
        if (timeFont  != null) { timeFont.close();  timeFont  = null; }
        if (helloPath != null) { helloPath.close(); helloPath = null; }
    }

    @Override
    public void removed() {
        // Stop hello build thread (waits for the current Skija raster call to finish,
        // which is typically <20ms, before closing the path it depends on).
        helloBuildCancelled = true;
        if (helloBuildThread != null) {
            try { helloBuildThread.join(200L); } catch (InterruptedException ignored) {}
            helloBuildThread = null;
        }
        if (bgVideo != null) { bgVideo.dispose(); bgVideo = null; }
        bgTex.dispose();
        helloTex.dispose();
        dateTex.dispose();
        clearHelloFrames();
        closeFonts();
        lastBgIdx     = -1;
        lastBgFrameMs = -1;
    }

    @Override public boolean shouldPause()      { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
}
