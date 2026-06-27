package com.example.addon.modules;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import com.mojang.blaze3d.systems.RenderSystem;
import javax.imageio.ImageIO;
import javax.swing.plaf.synth.ColorType;

import org.lwjgl.glfw.GLFW;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import meteordevelopment.orbit.EventHandler;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.Option;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.option.ModeOption;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.example.addon.mixin.GuiGraphicsExtractorAccessor;
import com.example.addon.screens.SkiaPipRenderer;
import com.example.addon.screens.SkiaPipState;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import io.github.humbleui.skija.*;
import io.github.humbleui.types.*;

public class MusicHUD extends AddonModule {
    public static final MusicHUD INSTANCE = new MusicHUD();
    public boolean active = false;

    public enum BackGroundMode {
        Blur("Blur"), LiquidGlass("Liquid Glass");
        private final String text;
        BackGroundMode(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }
    public enum LerpMode {
        Full("Full"), Semi("Semi"), Off("Off");
        private final String text;
        LerpMode(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }
    public enum ButtonEffect {
        Splash("Splash"), Topo("Topo"), Gradient("Gradient");
        private final String text;
        ButtonEffect(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }
    public double posX = com.example.addon.util.HudPositions.getX("MusicHUD", 10.0);
    public double posY = com.example.addon.util.HudPositions.getY("MusicHUD", 10.0);
    public final SliderOption barAlpha = new SliderOption(this, "BarAlpha",  "Transparency of the visualizer bars (0-255).", 160.0,  0.0,  255.0, 1.0);
    public final ToggleOption openFontUi = new ToggleOption(this, "AddFont", "", false);
    public final ToggleOption gradientBars = new ToggleOption(this, "GradientBars", "", true);
    public final ModeOption<BackGroundMode> bgMode = new ModeOption<>(this, "BackGround", "Background style of the HUD.", BackGroundMode.LiquidGlass);
    public final SliderOption blurIntensity = new SliderOption(this, "BlurIntensity", "Intensity of the blur effect.", 3.0, 0.0, 50.0, 1.0, () -> bgMode.getValue() != BackGroundMode.LiquidGlass);
    public final SliderOption titleFontSize = new SliderOption(this, "TitleFontSize", "Size of the track title font (TextBloomPlus only)", 10.0, 5.0, 30.0, 0.5);
    public final SliderOption authorFontSize = new SliderOption(this, "AuthorFontSize", "Size of the artist name font (TextBloomPlus only)", 9.0, 5.0, 30.0, 0.5);
    public final ToggleOption textBloom = new ToggleOption(this, "TextBloom", "Vanilla Text Bloom", false, () -> this.textBloomPlus == null || !this.textBloomPlus.getValue());
    public final ToggleOption textBloomPlus = new ToggleOption(this, "TextBloomPlus", "", true, () -> this.textBloom == null || !this.textBloom.getValue());
    public final ToggleOption compactMode = new ToggleOption(this, "CompactMode", "Collapse the HUD, limiting its height.", false);
    public final ToggleOption disk = new ToggleOption(this, "Disk", "Enable to show the spinning record.", true);
    public final ToggleOption ultraDisk = new ToggleOption(this, "UltraDisk", "Only display the record, hide everything else.", false, () -> disk.getValue());
    public final SliderOption diskSize = new SliderOption(this, "DiskSize", "Size of the music disk (Ultra Disk).", 100.0, 30.0, 300.0, 1.0);
    
    public final ModeOption<LerpMode> lerpMode = new ModeOption<>(this, "Lerp Mode", "Lerp mode for the audio bars.", LerpMode.Full);
    public final ModeOption<ButtonEffect> buttonEffect = new ModeOption<>(this, "Button Effect", "Visual effect when clicking buttons.", ButtonEffect.Gradient);
    private final java.util.List<java.io.File> availableFonts = new java.util.ArrayList<>();
    private java.io.File activeFontFile = null;
    private String lastFontPath = "";
    private static final int    BAR_COUNT  = 15;
    private static final int    THUMB_W    = 55;
    private static final int    THUMB_H    = 55;
    private static final int    HUD_HEIGHT = 70;
    private static final Identifier VINYL_TEX = Identifier.fromNamespaceAndPath("musichud", "vinyl.png");
    private static final Identifier PLAY_ICON = Identifier.fromNamespaceAndPath("musichud", "play.png");
    private static final Identifier PAUSE_ICON = Identifier.fromNamespaceAndPath("musichud", "pause-button.png");
    private static final Identifier PREV_ICON = Identifier.fromNamespaceAndPath("musichud", "previous.png");
    private static final Identifier NEXT_ICON = Identifier.fromNamespaceAndPath("musichud", "next-button.png");

    private boolean isDraggingHUD = false;
    private double dragOffsetX = 0, dragOffsetY = 0;
    private boolean wasMouseDownEditor = false;

    private String currentTrackId = "";
    private String displayTitle   = "Not playing";
    private String displayAuthor  = "";

    private Color accentColor     = new Color(0, 255, 150, 255);
    private Color targetAccent    = new Color(0, 255, 150, 255);

    private int    transPhase     = 0;
    private double animW          = 6.0; 
    private static final double LERP_OPEN  = 0.05; 
    private static final double LERP_CLOSE = 0.07;
    private static final double WIDTH_MIN  = 6.0;
    
    private long   hudHoverStartTime = 0; 
    private long   lastRenderTime = 0;
    private float  smoothDiskRotation = 0f;

    private boolean isManuallyStopped = true;
    private long    trackEmptyStartTime = 0; 
    private boolean isFirstRender = true; 
    private boolean forceCloseForNewTrack = false;
    private int currentPlayCount = -1;
    private boolean wasSpotifyOverride = false;

    private boolean isDraggingWidth = false;
    private double manualTargetWidth = -1.0;

    private volatile byte[]   pendingThumbBytesSquare = null;
    private volatile byte[]   pendingThumbBytesCircle = null;
    private Identifier         thumbSquareId    = null;
    private Identifier         thumbCircleId    = null;
    private DynamicTexture thumbTexSquare   = null;
    private DynamicTexture thumbTexCircle   = null;
    private boolean            thumbLoading     = false;
    private int                thumbGen         = 0;

    private final float[] barHeights = new float[BAR_COUNT];
    private float smoothedAmp = 0f;
    private float waveProgress = 1.0f;
    private double waveX = 0;
    private double waveY = 0;

    private void triggerWave(double x, double y) {
        this.waveX = x;
        this.waveY = y;
        this.waveProgress = 0.0f;
    }
    private boolean isDragging = false, wasMouseDown = false;
    private boolean hoverPrev = false, hoverPlay = false, hoverNext = false, hoverProgress = false;
    // Vị trí xem trước khi đang kéo thanh tiến trình (0.0 - 1.0).
    // Khi đang kéo, UI luôn vẽ theo giá trị này thay vì track.getPosition(),
    // vì audio engine không seek liên tục mỗi frame (sẽ giật/lag).
    private double dragPreviewRatio = -1; // -1 = không đang kéo
    private long   lastSeekSentAt   = 0;  // throttle gọi PlayMusic.seekTo() thật
    // Freeze PlayMusic position display when paused so the bar never drifts.
    private long   frozenPlayMusicPositionMs = -1;

    /**
     * Everything paintSkia() needs, computed by render() during extraction and
     * consumed once during the Picture-in-Picture render pass (SkiaPipRenderer) that
     * render() registers via registerSkiaPip(). render() itself never draws — it only
     * updates state/animation and fills this snapshot.
     */
    private static final class PaintState {
        double x, y, hudW, hudH;
        boolean useUltra;
        Color accent;
        boolean liquidGlass, enableGlow, gpuBlur;
        float radius;

        boolean notPlaying;

        boolean useDisk;
        Identifier activeThumbId;
        int thumbX, thumbY, thumbW, thumbH;
        float diskRotationDeg;

        boolean showHoldProgress;
        int holdX1, holdY1, holdX2, holdY2;

        boolean showContent;
        double contentX, contentW;
        String titleText = "", authorText = "";
        boolean textBloomPlus, textBloom;

        AudioTrack track;
        double barsX, barsY, barsW;

        String timeText;
        double timeX, timeY;

        double btnPrevX, btnPlayX, btnNextX, btnY;
        int iconSz;
        boolean hoverPrev, hoverPlay, hoverNext, hoverProgress;
        double progX, progY, pBarX, pBarTop;
        int pBarW, filledW;
        double waveX, waveY;
        float waveProgress;
        ButtonEffect buttonEffect;
        Identifier playPauseIcon;

        boolean showOutline;
        int outlineX, outlineY, outlineW, outlineH, outlineColor;
    }

    private PaintState paintState;
    private Font skiaFontBody;

    private MusicHUD() {
        super("MusicHUD", "Music player HUD with Skia Rounded Corners & Glow.");
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("example-addon", "musichud"), (context, tracker) -> {
            if (this.active) render(context);
        });
    }

    @Override public void onEnable()  {
        this.active = true;
        lastRenderTime = System.currentTimeMillis();
        isFirstRender = true;
    }
    @Override public void onDisable() {
        this.active = false;
        paintState = null;
    }

    // Null-safe config load: bỏ qua option thiếu trong config cũ (vd option "BackGround"
    // mới thêm) thay vì ném NPE — tránh làm hỏng vòng nạp module khiến các module khác tắt.
    @Override
    public AddonModule fromJson(JsonObject object) {
        try {
            if (object.has("title"))            setTitle(object.get("title").getAsString());
            if (object.has("state"))            setState(object.get("state").getAsBoolean());
            if (object.has("visible"))          setVisible(object.get("visible").getAsBoolean());
            if (object.has("notify"))           setNotify(object.get("notify").getAsBoolean());
            if (object.has("onlyWhileHolding")) setOnlyWhileHolding(object.get("onlyWhileHolding").getAsBoolean());
            for (Option<?> setting : options) {
                try {
                    JsonElement el = object.get(setting.name);
                    if (el != null && el.isJsonObject()) setting.fromJson(el.getAsJsonObject());
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return this;
    }
    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (openFontUi.getValue()) {
            openFontUi.setValue(false); 
            mc.execute(() -> mc.setScreen(new FontScreen()));
        }
    }
    private void paintOutline(Canvas canvas, int x, int y, int w, int h, int argb) {
        try (Paint paint = new Paint()) {
            paint.setColor(argb);
            canvas.drawRect(Rect.makeXYWH(x - 1, y - 1, w + 2, 1), paint);
            canvas.drawRect(Rect.makeXYWH(x - 1, y + h, w + 2, 1), paint);
            canvas.drawRect(Rect.makeXYWH(x - 1, y, 1, h), paint);
            canvas.drawRect(Rect.makeXYWH(x + w, y, 1, h), paint);
        }
    }

    /**
     * Paints the panel decoration (glow + stroke [+ dark fill if no GPU blur] for
     * "Blur" mode, or tint + gradient + glint + glow + rim for "LiquidGlass" mode) on
     * top of whatever the GPU backdrop-blur pass already wrote. Runs at end-of-frame
     * on the persistent canvas — drawBackground() already triggered the blur pass and
     * filled the PaintState fields this reads.
     */
    private void paintBackgroundPanel(Canvas canvas) {
        PaintState p = paintState;
        float fx = (float) p.x, fy = (float) p.y, fw = (float) p.hudW, fh = (float) p.hudH;
        RRect panel = RRect.makeXYWH(fx, fy, fw, fh, p.radius);

        if (p.liquidGlass) {
            canvas.save();
            canvas.clipRRect(panel, ClipMode.INTERSECT, true);

            try (Paint tintPaint = new Paint()) {
                tintPaint.setColor(new Color(150, 205, 255, 10).getRGB());
                tintPaint.setAntiAlias(true);
                canvas.drawRRect(panel, tintPaint);
            }
            try (Shader radShader = Shader.makeRadialGradient(
                     fx + fw * 0.5f, fy + fh * 0.45f, Math.max(fw, fh) * 0.85f,
                     new int[]{ 0x0CFFFFFF, 0x04FFFFFF, 0x14000000 }, new float[]{ 0f, 0.55f, 1f });
                 Paint radPaint = new Paint()) {
                radPaint.setShader(radShader);
                radPaint.setAntiAlias(true);
                canvas.drawRRect(panel, radPaint);
            }
            float gcx = fx + p.radius * 1.6f, gcy = fy + p.radius * 1.2f;
            try (Shader glint = Shader.makeRadialGradient(
                     gcx, gcy, Math.max(8f, p.radius * 2.4f),
                     new int[]{ 0x40FFFFFF, 0x00FFFFFF }, new float[]{ 0f, 1f });
                 Paint gp = new Paint()) {
                gp.setShader(glint);
                gp.setAntiAlias(true);
                canvas.drawRect(Rect.makeXYWH(fx, fy, fw, fh), gp);
            }
            canvas.restore();

            if (p.enableGlow && p.accent != null) {
                int ac = (0x22 << 24) | (p.accent.getRGB() & 0x00FFFFFF);
                try (Paint glowPaint = new Paint();
                     ImageFilter glowFilter = ImageFilter.makeDropShadowOnly(0, 0, 16f, 16f, ac)) {
                    glowPaint.setImageFilter(glowFilter);
                    glowPaint.setAntiAlias(true);
                    canvas.drawRRect(panel, glowPaint);
                }
            }
            try (Paint rim = new Paint()) {
                rim.setColor(new Color(200, 225, 255, 36).getRGB());
                rim.setMode(PaintMode.STROKE);
                rim.setStrokeWidth(0.8f);
                rim.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH(fx + 0.5f, fy + 0.5f, fw - 1f, fh - 1f, p.radius), rim);
            }
            return;
        }

        if (p.enableGlow && p.accent != null) {
            try (Paint glowPaint = new Paint();
                 MaskFilter glowBlur = MaskFilter.makeBlur(FilterBlurMode.OUTER, 8f)) {
                glowPaint.setColor(p.accent.getRGB());
                glowPaint.setMode(PaintMode.STROKE);
                glowPaint.setStrokeWidth(2.5f);
                glowPaint.setMaskFilter(glowBlur);
                glowPaint.setAntiAlias(true);
                canvas.drawRRect(panel, glowPaint);
            }
        }
        // Only fill a dark body when there's no GPU frosted blur behind it; with the
        // blur on, the GPU pass already provides the (darkened) panel body.
        if (!p.gpuBlur) {
            try (Paint bgPaint = new Paint()) {
                bgPaint.setColor(new Color(15, 15, 15, 150).getRGB());
                bgPaint.setAntiAlias(true);
                canvas.drawRRect(panel, bgPaint);
            }
        }
        try (Paint strokePaint = new Paint()) {
            strokePaint.setColor(new Color(255, 255, 255, 60).getRGB());
            strokePaint.setMode(PaintMode.STROKE);
            strokePaint.setStrokeWidth(1.0f);
            strokePaint.setAntiAlias(true);
            canvas.drawRRect(panel, strokePaint);
        }
    }

    /**
     * Triggers the GPU backdrop-blur pass (if any) and records everything paintSkia()
     * needs to draw the panel decoration. Must run during extraction (the blur trigger
     * calls are extraction-only APIs); the actual pixels are painted later, at
     * end-of-frame, by paintBackgroundPanel(Canvas).
     */
    private void drawBackground(GuiGraphicsExtractor context, double x, double y, double w, double h, float radius, Color accent, boolean enableGlow) {
        PaintState p = paintState;
        p.liquidGlass = bgMode.getValue() == BackGroundMode.LiquidGlass;
        p.radius = radius;
        p.enableGlow = enableGlow;
        p.accent = accent;

        Minecraft mc = Minecraft.getInstance();
        float scale = (float) mc.getWindow().getGuiScale();

        if (p.liquidGlass) {
            int fbH = mc.getMainRenderTarget().height;
            // Mark the blur boundary: GuiRenderer only calls GameRenderer.processBlurEffect()
            // (which our mixin intercepts to run the GPU blur+refraction pass) if something
            // requested it via blurBeforeThisStratum() during extraction.
            context.blurBeforeThisStratum();
            LiquidGlassHud.INSTANCE.setWidget((float)x, (float)y, (float)w, (float)h, radius, scale, fbH);
            p.gpuBlur = true;
        } else {
            float blurVal = (float)(double) blurIntensity.getValue();
            p.gpuBlur = blurVal > 0.5f;
            if (p.gpuBlur) {
                context.blurBeforeThisStratum();
                SkijaBackdropBlur.INSTANCE.setWidget((float)x, (float)y, (float)w, (float)h, radius, scale, blurVal, 0.35f);
            }
        }
    }

    

    private Color lerpColor(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
            (int)(a.getRed()   + (b.getRed()   - a.getRed())   * t),
            (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t),
            (int)(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t)
        );
    }

    private double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private double calcTargetWidth(Minecraft mc) {
        if (compactMode.getValue()) return THUMB_W + 10 + 130.0;
        double maxTextW;
        // Title/author always render via the Skija fonts now (regardless of TextBloomPlus),
        // so width must always be measured with those, not mc.font.
        if (skiaFontTitle != null && skiaFontAuthor != null) {
            maxTextW = Math.max(skiaFontTitle.measureTextWidth(displayTitle), skiaFontAuthor.measureTextWidth(displayAuthor));
        } else {
            maxTextW = Math.max(mc.font.width(displayTitle), mc.font.width(displayAuthor));
        }
        return Math.ceil((THUMB_W + 10 + Math.max(160, maxTextW + 60)) / 10.0) * 10.0;
    }

    private void handleMouse(AudioTrack track, double hudX, double hudY, double hudW, double hudH) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            isDragging = wasMouseDown = hoverPrev = hoverPlay = hoverNext = hoverProgress = false;
            isDraggingWidth = false;
            dragPreviewRatio = -1;
            hudHoverStartTime = 0; return;
        }

        boolean spotifyOverride = SpotifyIntegration.isSpotifyConnected;
        boolean useUltra = disk.getValue() && ultraDisk.getValue();
        double scale  = mc.getWindow().getGuiScale();
        double mouseX = mc.mouseHandler.xpos() / scale;
        double mouseY = mc.mouseHandler.ypos() / scale;

        long win = mc.getWindow().handle();
        boolean mouseDown = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean justPressed  = mouseDown && !wasMouseDown;
        boolean justReleased = !mouseDown && wasMouseDown;
        wasMouseDown = mouseDown;


        double edgeX = hudX + hudW;
        boolean hoverEdge = !useUltra && mouseX >= edgeX - 6 && mouseX <= edgeX + 6 && mouseY >= hudY && mouseY <= hudY + hudH;
        double contentX = hudX + THUMB_W + 10;
        double pBarX    = contentX;
        double pBarW    = hudW - (THUMB_W + 10) - 10;
        double pBarY    = hudY + hudH - 14;
        double btnY     = hudY + 35;
        double btnPrevX = contentX;
        double btnPlayX = contentX + 25;
        double btnNextX = contentX + 50;

        hoverPrev = mouseX >= btnPrevX && mouseX <= btnPrevX + 15 && mouseY >= btnY && mouseY <= btnY + 12;
        hoverPlay = mouseX >= btnPlayX && mouseX <= btnPlayX + 15 && mouseY >= btnY && mouseY <= btnY + 12;
        hoverNext = mouseX >= btnNextX && mouseX <= btnNextX + 15 && mouseY >= btnY && mouseY <= btnY + 12;
        // Progress bar works for both PlayMusic (needs track) and Spotify (needs durationMs).
        hoverProgress = (track != null || (spotifyOverride && SpotifyIntegration.durationMs > 0))
                        && mouseX >= pBarX && mouseX <= pBarX + pBarW && mouseY >= pBarY && mouseY <= pBarY + 10;

        boolean hoverHud = mouseX >= hudX && mouseX <= hudX + hudW && mouseY >= hudY && mouseY <= hudY + hudH;
        double currentDiskW = useUltra ? (int)(double)diskSize.getValue() : THUMB_W;
        double currentDiskH = useUltra ? (int)(double)diskSize.getValue() : THUMB_H;
        double diskX = useUltra ? hudX : hudX + 5;
        double diskY = useUltra ? hudY : hudY + 7;
        double centerX = diskX + currentDiskW / 2.0;
        double centerY = diskY + currentDiskH / 2.0;
        double radius = currentDiskW / 2.0;
        boolean hoverUltraDisk = useUltra && Math.sqrt(Math.pow(mouseX - centerX, 2) + Math.pow(mouseY - centerY, 2)) <= radius;

        if (justPressed) {
            if (hoverEdge) {
                isDraggingWidth = true;
                isDragging = false;
            } else if (useUltra) {
                if (hoverUltraDisk) {
                    if (spotifyOverride) SpotifyIntegration.controlPlayPause();
                    else PlayMusic.INSTANCE.togglePause.setValue(true);
                }
            } else {
                if (hoverPlay) {
                    if (spotifyOverride) SpotifyIntegration.controlPlayPause();
                    else PlayMusic.INSTANCE.togglePause.setValue(true);
                    triggerWave(btnPlayX + 6, btnY + 6);
                } else if (hoverPrev) {
                    if (spotifyOverride) SpotifyIntegration.controlPrevious();
                    else PlayMusic.INSTANCE.previousBtn.setValue(true);
                    triggerWave(btnPrevX + 6, btnY + 6);
                } else if (hoverNext) {
                    if (spotifyOverride) SpotifyIntegration.controlNext();
                    else PlayMusic.INSTANCE.nextBtn.setValue(true);
                    triggerWave(btnNextX + 6, btnY + 6);
                } else if (hoverProgress) {
                    isDragging = true;
                    dragPreviewRatio = Math.max(0, Math.min(1, (mouseX - pBarX) / pBarW));
                }
            }
        }
        if (mouseDown && isDraggingWidth) {
            manualTargetWidth = Math.max(THUMB_W + 120.0, mouseX - hudX);
        }

        if (mouseDown && isDragging && !useUltra) {
            dragPreviewRatio = Math.max(0, Math.min(1, (mouseX - pBarX) / pBarW));
            long nowMs = System.currentTimeMillis();
            if (spotifyOverride) {
                if (nowMs - lastSeekSentAt >= 200) {
                    SpotifyIntegration.controlSeek((long)(dragPreviewRatio * SpotifyIntegration.durationMs));
                    lastSeekSentAt = nowMs;
                }
            } else if (track != null && nowMs - lastSeekSentAt >= 200) {
                PlayMusic.seekTo((long)(dragPreviewRatio * track.getDuration()));
                lastSeekSentAt = nowMs;
            }
        }

        boolean isHoveringTarget = useUltra ? hoverUltraDisk : (hoverHud && !isDragging && !hoverPlay && !hoverPrev && !hoverNext && !isDraggingWidth && !hoverEdge);
        if (mouseDown && isHoveringTarget) {
            if (hudHoverStartTime == 0) hudHoverStartTime = System.currentTimeMillis();
            else if (System.currentTimeMillis() - hudHoverStartTime >= 2500) {
                PlayMusic.stopCurrentTrack();
                isManuallyStopped = true;
                hudHoverStartTime = 0;
            }
        } else {
            hudHoverStartTime = 0;
        }
        if (justReleased) {
            if (isDragging && !useUltra) {
                if (spotifyOverride && dragPreviewRatio >= 0) {
                    SpotifyIntegration.controlSeek((long)(dragPreviewRatio * SpotifyIntegration.durationMs));
                } else if (track != null) {
                    seekToMouse(mouseX, pBarX, pBarW, track);
                }
            }
            isDragging = false;
            isDraggingWidth = false;
            dragPreviewRatio = -1;
        }
    }

    private void seekToMouse(double mx, double pBarX, double pBarW, AudioTrack track) {
        double ratio = Math.max(0, Math.min(1, (mx - pBarX) / pBarW));
        PlayMusic.seekTo((long)(ratio * track.getDuration()));
    }
    
    private void initSkiaFontsIfNeeded() {
        Minecraft mc = Minecraft.getInstance();
        float tSize = (float)(double) titleFontSize.getValue();
        float aSize = (float)(double) authorFontSize.getValue();

        java.io.File dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile();
        java.io.File musicFontDir = new java.io.File(dir, "boze/musicfont");
        if (!musicFontDir.exists()) musicFontDir.mkdirs();

        java.io.File[] files = musicFontDir.listFiles((d, name) -> name.toLowerCase().endsWith(".ttf") || name.toLowerCase().endsWith(".otf"));
        java.util.List<java.io.File> scannedFonts = new java.util.ArrayList<>();
        if (files != null) {
            for (java.io.File f : files) scannedFonts.add(f);
        }
        
        synchronized(this) {
            this.availableFonts.clear();
            this.availableFonts.addAll(scannedFonts);
            
            // TỰ ĐỘNG ĐỌC LẠI FONT ĐÃ LƯU TỪ LẦN TRƯỚC
            if (activeFontFile == null) {
                java.io.File saveFile = new java.io.File(dir, "boze/musicfont_save.txt");
                if (saveFile.exists()) {
                    try {
                        String savedPath = java.nio.file.Files.readString(saveFile.toPath()).trim();
                        if (!savedPath.isEmpty()) {
                            java.io.File sf = new java.io.File(savedPath);
                            if (sf.exists()) activeFontFile = sf;
                        }
                    } catch (Exception e) {}
                }
            }
            if (activeFontFile != null && !activeFontFile.exists()) activeFontFile = null;
        }

        java.io.File targetFontFile = activeFontFile;
        if (targetFontFile == null) {
            targetFontFile = new java.io.File(dir, "boze/musichud_font.otf");
            if (!targetFontFile.exists()) targetFontFile = new java.io.File(dir, "boze/musichud_font.ttf");
        }

        long currentModTime = targetFontFile.exists() ? targetFontFile.lastModified() : -1L;
        String fontIdentifier = targetFontFile.exists() ? targetFontFile.getAbsolutePath() : "Arial";

        if (skiaFontTitle == null || lastTitleSize != tSize || lastAuthorSize != aSize || lastFontModifiedTime != currentModTime || !fontIdentifier.equals(lastFontPath)) {
            if (skiaFontTitle != null) skiaFontTitle.close();
            if (skiaFontAuthor != null) skiaFontAuthor.close();

            Typeface typeface = null;
            if (targetFontFile.exists()) {
                try {
                    byte[] fontBytes = java.nio.file.Files.readAllBytes(targetFontFile.toPath());
                    typeface = FontMgr.getDefault().makeFromData(Data.makeFromBytes(fontBytes));
                } catch (Exception ignored) {}
            }
            if (typeface == null) typeface = FontMgr.getDefault().matchFamilyStyle("Arial", FontStyle.BOLD);
            if (typeface == null) typeface = FontMgr.getDefault().matchFamilyStyle(null, FontStyle.NORMAL);
            
            skiaFontTitle = new Font(typeface, tSize);
            skiaFontAuthor = new Font(typeface, aSize);
            lastTitleSize = tSize;
            lastAuthorSize = aSize;
            lastFontModifiedTime = currentModTime;
            lastFontPath = fontIdentifier;
        }

        // Small fixed-size body font (time, "Not playing", note glyph) — deliberately
        // the default system typeface, not the user's custom music font, so it keeps
        // looking like Minecraft's own UI text regardless of what font is loaded above.
        if (skiaFontBody == null) {
            Typeface bodyFace = FontMgr.getDefault().matchFamilyStyle(null, FontStyle.NORMAL);
            skiaFontBody = new Font(bodyFace, 9f);
        }
    }

    private void render(GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui && !(mc.screen instanceof MusicHUD.FontScreen)) { paintState = null; return; }
        initSkiaFontsIfNeeded();
        PaintState p = new PaintState();
        this.paintState = p;

        long now = System.currentTimeMillis();
        long deltaMs = now - lastRenderTime;
        if (waveProgress < 1.0f) {
            waveProgress += (deltaMs / 600.0f); // Sóng lan tỏa trong 600ms
            if (waveProgress > 1.0f) waveProgress = 1.0f;
        }
        lastRenderTime = now;
        boolean useUltra = disk.getValue() && ultraDisk.getValue();
        p.useUltra = useUltra;

        if (pendingThumbBytesSquare != null && pendingThumbBytesCircle != null) uploadPendingThumb(mc);

        double x = posX;
        double y = posY;
        AudioTrack track = PlayMusic.getCurrentTrack();
        // Use isSpotifyConnected (not isSpotifyPlaying) so the HUD stays visible while Spotify is paused.
        boolean spotifyOverride = SpotifyIntegration.isSpotifyConnected;

        boolean isTrackPlaying = spotifyOverride || (track != null &&
                                 track.getState() != AudioTrackState.INACTIVE &&
                                 track.getState() != AudioTrackState.STOPPING &&
                                 track.getState() != AudioTrackState.FINISHED);

        if (isFirstRender) {
            if (!isTrackPlaying) { animW = WIDTH_MIN; transPhase = 1; }
            else { animW = calcTargetWidth(mc); transPhase = 2; }
            if (lerpMode.getValue() == LerpMode.Off && isTrackPlaying) animW = calcTargetWidth(mc);
            isFirstRender = false;
        }

        if (isTrackPlaying) isManuallyStopped = false;
        boolean isActuallyPlaying = spotifyOverride ? SpotifyIntegration.isSpotifyPlaying : !PlayMusic.isPlayerPaused();
        if (isTrackPlaying && isActuallyPlaying) {
            smoothDiskRotation += deltaMs * 0.045f;
            smoothDiskRotation %= 360f;
        }

        accentColor = lerpColor(accentColor, targetAccent, 0.015f);
        p.accent = accentColor;

        double hudW = Math.max(animW, WIDTH_MIN);
        int currentThumbW = useUltra ? (int)(double)diskSize.getValue() : THUMB_W;
        int currentThumbH = useUltra ? (int)(double)diskSize.getValue() : THUMB_H;
        double hudH = useUltra ? currentThumbH : HUD_HEIGHT;
        p.x = x; p.y = y; p.hudW = hudW; p.hudH = hudH;

        if (isManuallyStopped) {
            if (lerpMode.getValue() == LerpMode.Off) animW = WIDTH_MIN;
            else                                      animW = lerp(animW, WIDTH_MIN, LERP_CLOSE);

            if (!useUltra) {
                p.hudH = HUD_HEIGHT;
                drawBackground(context, x, y, animW, HUD_HEIGHT, 8f, accentColor, false);
                p.notPlaying = true;
                registerSkiaPip(context, x, y, animW, HUD_HEIGHT, 0);
            }
            return;
        }

        double calculatedTarget = calcTargetWidth(mc);
        double newTarget = (manualTargetWidth != -1.0 && !compactMode.getValue()) ? manualTargetWidth : calculatedTarget;

        double closeTarget = WIDTH_MIN;
        if (lerpMode.getValue() == LerpMode.Semi) {
            closeTarget = newTarget / 3.0;
        }

        if (!isTrackPlaying) {
            if (trackEmptyStartTime == 0) trackEmptyStartTime = System.currentTimeMillis();
            if (System.currentTimeMillis() - trackEmptyStartTime > 1000) {
                if (lerpMode.getValue() == LerpMode.Off) {
                    animW = WIDTH_MIN;
                    isManuallyStopped = true;
                } else {
                    if (transPhase == 0) transPhase = 1;
                    if (animW <= closeTarget + 4.0) {
                        isManuallyStopped = true;
                        currentTrackId = "";
                        displayTitle = "Not playing";
                        displayAuthor = "";
                        thumbSquareId = null;
                        thumbCircleId = null;
                        manualTargetWidth = -1.0;
                    }
                }
            }
        } else {
            trackEmptyStartTime = 0;
            // Force a display refresh whenever the source switches (Spotify ↔ PlayMusic).
            // Without this, if spotifyPlayCount == PlayMusic.playCount the update is silently skipped.
            if (spotifyOverride != wasSpotifyOverride) {
                currentPlayCount = -1;
                wasSpotifyOverride = spotifyOverride;
            }
            int effectivePlayCount = spotifyOverride ? SpotifyIntegration.spotifyPlayCount : PlayMusic.playCount;
            if (effectivePlayCount != currentPlayCount) {
                currentPlayCount = effectivePlayCount;

                if (lerpMode.getValue() == LerpMode.Off) {
                    transPhase = 0;
                    forceCloseForNewTrack = false;
                    animW = newTarget;
                } else {
                    forceCloseForNewTrack = true;
                    transPhase = 1;
                }

                if (spotifyOverride) {
                    displayTitle  = SpotifyIntegration.currentTitle;
                    displayAuthor = SpotifyIntegration.currentArtist;
                    // Thumbnail and accent color are loaded by SpotifyIntegration when the track changes.
                } else {
                    currentTrackId = track.getIdentifier();
                    displayTitle   = track.getInfo().title;
                    displayAuthor  = track.getInfo().author;
                    extractCinematicColorsAsync(currentTrackId);
                    loadThumbnailAsync(currentTrackId);
                }
            }
        }

        if (lerpMode.getValue() == LerpMode.Off) {
            if (isDraggingWidth) animW = lerp(animW, newTarget, 0.2);
            else { animW = newTarget; transPhase = 0; }
        } else {
            if (transPhase == 1) {
                animW = lerp(animW, closeTarget, LERP_CLOSE);
                if (animW <= closeTarget + 4.0 && forceCloseForNewTrack) {
                    forceCloseForNewTrack = false;
                    transPhase = 2;
                }
            } else if (transPhase == 2) {
                animW = lerp(animW, newTarget, LERP_OPEN);
                if (Math.abs(animW - newTarget) < 1.5) { animW = newTarget; transPhase = 0; }
            } else {
                animW = lerp(animW, newTarget, isDraggingWidth ? 0.2 : 0.04);
            }
        }

        handleMouse(track, x, y, hudW, hudH);

        int currentThumbX = useUltra ? (int)x : (int)x + 5;
        int currentThumbY = useUltra ? (int)y : (int)y + 7;
        p.useDisk = disk.getValue();
        p.thumbX = currentThumbX; p.thumbY = currentThumbY; p.thumbW = currentThumbW; p.thumbH = currentThumbH;
        p.activeThumbId = p.useDisk ? thumbCircleId : thumbSquareId;
        p.diskRotationDeg = smoothDiskRotation;

        if (!useUltra) {
            drawBackground(context, x, y, hudW, HUD_HEIGHT, 8f, accentColor, true);
        } else {
            p.radius = currentThumbW / 2.0f; // reused field to carry the ultra-disk glow radius
        }

        if (!useUltra) {
            if (hudHoverStartTime > 0 && !HUDEditor.INSTANCE.active) {
                long held = System.currentTimeMillis() - hudHoverStartTime;
                float holdProgress = Math.min(1.0f, held / 2500.0f);
                p.showHoldProgress = true;
                p.holdX1 = (int)x + 3; p.holdY1 = (int)(y + HUD_HEIGHT - 2);
                p.holdX2 = (int)(x + hudW * holdProgress - 3); p.holdY2 = (int)(y + HUD_HEIGHT);
            }

            double contentX = x + THUMB_W + 10;
            double contentW = hudW - THUMB_W - 10 - 8;

            if (hudW > THUMB_W + 30) {
                p.showContent = true;
                p.contentX = contentX; p.contentW = contentW;
                p.titleText  = skiaFontTitle  != null ? clipText(skiaFontTitle,  displayTitle,  (float)contentW - 30) : displayTitle;
                p.authorText = skiaFontAuthor != null ? clipText(skiaFontAuthor, displayAuthor, (float)contentW - 30) : displayAuthor;
                p.textBloomPlus = textBloomPlus.getValue();
                p.textBloom = textBloom.getValue();
                p.barsX = contentX + 75; p.barsY = y; p.barsW = contentW - 75;

                long s, ds;
                if (spotifyOverride) {
                    long elapsed = SpotifyIntegration.isSpotifyPlaying
                        ? (System.currentTimeMillis() - SpotifyIntegration.progressFetchedAt) : 0;
                    long pos = Math.min(SpotifyIntegration.progressMs + elapsed, SpotifyIntegration.durationMs);
                    s = pos / 1000; ds = SpotifyIntegration.durationMs / 1000;
                } else if (track != null) {
                    if (!PlayMusic.isPlayerPaused()) frozenPlayMusicPositionMs = track.getPosition();
                    long posMs = (PlayMusic.isPlayerPaused() && frozenPlayMusicPositionMs >= 0)
                        ? frozenPlayMusicPositionMs : track.getPosition();
                    s = posMs / 1000; ds = track.getDuration() / 1000;
                    if (!isTrackPlaying) s = ds;
                } else { s = 0; ds = 0; }
                if (ds > 0) {
                    p.timeText = String.format("%02d:%02d / %02d:%02d", s/60, s%60, ds/60, ds%60);
                    p.timeX = x + hudW - 8; p.timeY = y + 35;
                } else {
                    p.timeText = null;
                }

                int iconSz = 12;
                int btnY = (int)y + 35;
                double btnPrevX = contentX + 2;
                double btnPlayX = contentX + 25;
                double btnNextX = contentX + 48;
                p.iconSz = iconSz; p.btnY = btnY;
                p.btnPrevX = btnPrevX; p.btnPlayX = btnPlayX; p.btnNextX = btnNextX;
                p.hoverPrev = hoverPrev; p.hoverPlay = hoverPlay; p.hoverNext = hoverNext; p.hoverProgress = hoverProgress;

                double progX = -1;
                double progY = y + HUD_HEIGHT - 8;
                int pBarW = (int)(contentW - 10);
                int filledW = 0;
                if (spotifyOverride && SpotifyIntegration.durationMs > 0) {
                    long elapsed = SpotifyIntegration.isSpotifyPlaying
                        ? (System.currentTimeMillis() - SpotifyIntegration.progressFetchedAt) : 0;
                    long pos = Math.min(SpotifyIntegration.progressMs + elapsed, SpotifyIntegration.durationMs);
                    double progress = (double) pos / SpotifyIntegration.durationMs;
                    filledW = Math.max(3, (int)(pBarW * progress));
                    progX = contentX + filledW;
                } else if (track != null) {
                    double progress;
                    if (dragPreviewRatio >= 0) {
                        progress = dragPreviewRatio;
                    } else if (track.getState() == AudioTrackState.FINISHED) {
                        progress = 1.0;
                    } else {
                        long posMs = (PlayMusic.isPlayerPaused() && frozenPlayMusicPositionMs >= 0)
                            ? frozenPlayMusicPositionMs : track.getPosition();
                        progress = (double) posMs / track.getDuration();
                    }
                    filledW = Math.max(3, (int)(pBarW * progress));
                    progX = contentX + filledW;
                }
                p.progX = progX; p.progY = progY; p.pBarX = contentX; p.pBarTop = y + HUD_HEIGHT - 10;
                p.pBarW = pBarW; p.filledW = filledW;
                p.waveX = waveX; p.waveY = waveY; p.waveProgress = waveProgress;
                p.buttonEffect = buttonEffect.getValue();

                p.playPauseIcon = (spotifyOverride ? !SpotifyIntegration.isSpotifyPlaying : (PlayMusic.isPlayerPaused() || !isTrackPlaying)) ? PLAY_ICON : PAUSE_ICON;
            }
        } else {
            if (hudHoverStartTime > 0 && !HUDEditor.INSTANCE.active) {
                long held = System.currentTimeMillis() - hudHoverStartTime;
                float holdProgress = Math.min(1.0f, held / 2500.0f);
                p.showHoldProgress = true;
                p.holdX1 = (int)x; p.holdY1 = (int)(y + currentThumbH + 2);
                p.holdX2 = (int)(x + currentThumbW * holdProgress); p.holdY2 = (int)(y + currentThumbH + 4);
            }
        }

        if (HUDEditor.INSTANCE.active) {
            double scale = mc.getWindow().getGuiScale();
            double mx = mc.mouseHandler.xpos() / scale;
            double my = mc.mouseHandler.ypos() / scale;
            boolean mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

            if (mouseDown && !wasMouseDownEditor) {
                if (mx >= x && mx <= x + hudW && my >= y && my <= y + hudH) {
                    if (HUDEditor.draggingHUD.isEmpty() || HUDEditor.draggingHUD.equals("MusicHUD")) {
                        isDraggingHUD = true; HUDEditor.draggingHUD = "MusicHUD";
                        dragOffsetX = mx - x; dragOffsetY = my - y;
                    }
                }
            } else if (!mouseDown) {
                if (isDraggingHUD) { HUDEditor.draggingHUD = ""; com.example.addon.util.HudPositions.save("MusicHUD", posX, posY); }
                isDraggingHUD = false;
            }

            if (isDraggingHUD && mouseDown) {
                x = mx - dragOffsetX; y = my - dragOffsetY;
                int screenW = mc.getWindow().getGuiScaledWidth();
                int screenH = mc.getWindow().getGuiScaledHeight();
                x = Math.max(0, Math.min(x, screenW - hudW));
                y = Math.max(0, Math.min(y, screenH - hudH));
                posX = x; posY = y;
                p.showOutline = true; p.outlineX = (int)x; p.outlineY = (int)y; p.outlineW = (int)hudW; p.outlineH = (int)hudH; p.outlineColor = 0xFF00FF00;
            } else if (mx >= x && mx <= x + hudW && my >= y && my <= y + hudH) {
                p.showOutline = true; p.outlineX = (int)x; p.outlineY = (int)y; p.outlineW = (int)hudW; p.outlineH = (int)hudH; p.outlineColor = 0xFFFFFF00;
            }
            wasMouseDownEditor = mouseDown;
        }

        double extentW = p.useUltra ? currentThumbW : hudW;
        double extentH = p.useUltra ? currentThumbH : hudH;
        registerSkiaPip(context, x, y, extentW, extentH, p.useUltra ? p.radius : 0);
    }

    /**
     * Registers this frame's MusicHUD paint as a Picture-in-Picture element so it gets
     * GPU-Skija rendering with correct Z-order (sorted alongside whatever Screen got
     * extracted before/after it this frame) instead of always drawing on top of
     * everything. extraGlow widens the bounds for the ultra-disk circular glow, which
     * can bleed well past the thumbnail's own rect.
     */
    private void registerSkiaPip(GuiGraphicsExtractor context, double x, double y, double w, double h, float extraGlow) {
        float margin = Math.max(60f, extraGlow * 1.5f);
        int x0 = (int) Math.floor(x - margin), y0 = (int) Math.floor(y - margin);
        int x1 = (int) Math.ceil(x + w + margin), y1 = (int) Math.ceil(y + h + margin);
        ((GuiGraphicsExtractorAccessor) context).getGuiRenderState()
            .addPicturesInPictureState(new SkiaPipState(this::paintSkia, x0, y0, x1, y1));
    }

    /**
     * Paints the entire HUD, using the snapshot render() filled into {@link #paintState}
     * this same frame. Called by SkiaPipRenderer from within its own offscreen-texture
     * render pass (correct Z-order, GPU Skija, no CPU raster) — every shape below is a
     * direct GPU draw call, none of it goes through an offscreen-raster+upload step.
     */
    private void paintSkia(Canvas canvas) {
        PaintState p = paintState;
        if (p == null) return;

        if (p.notPlaying) {
            paintBackgroundPanel(canvas);
            if (skiaFontBody != null) {
                try (Paint sh = new Paint(); Paint tp = new Paint()) {
                    sh.setColor(0x80000000); tp.setColor(0xFFFFFFFF);
                    sh.setAntiAlias(true); tp.setAntiAlias(true);
                    float bx = (float) p.x + 15, by = (float) p.y + 28 + 7;
                    canvas.drawString("Not playing", bx + 1, by + 1, skiaFontBody, sh);
                    canvas.drawString("Not playing", bx, by, skiaFontBody, tp);
                }
            }
            return;
        }

        if (!p.useUltra) {
            paintBackgroundPanel(canvas);
        } else {
            paintCircularGlow(canvas, p.thumbX + p.thumbW / 2.0, p.thumbY + p.thumbH / 2.0, p.radius, p.accent);
        }

        paintThumbnail(canvas, p.useDisk, p.activeThumbId, p.thumbX, p.thumbY, p.thumbW, p.thumbH, p.diskRotationDeg);

        if (p.showHoldProgress) {
            try (Paint hp = new Paint()) {
                hp.setColor(0xFFFF3333);
                canvas.drawRect(Rect.makeLTRB(p.holdX1, p.holdY1, p.holdX2, p.holdY2), hp);
            }
        }

        if (p.showContent) {
            paintBars(canvas, p.barsX, p.barsY, p.barsW);
            paintTitleAuthor(canvas, p.contentX, p.y, p.titleText, p.authorText, p.accent, p.textBloomPlus, p.textBloom);

            if (p.timeText != null && skiaFontBody != null) {
                float tw = skiaFontBody.measureTextWidth(p.timeText);
                float bx = (float) p.timeX - tw, by = (float) p.timeY + 7;
                try (Paint tp = new Paint()) {
                    tp.setColor(0xFFBBBBBB);
                    tp.setAntiAlias(true);
                    canvas.drawString(p.timeText, bx, by, skiaFontBody, tp);
                }
            }

            paintLiquidUI(canvas, p.btnPrevX, p.btnPlayX, p.btnNextX, p.btnY, p.iconSz,
                p.hoverPrev, p.hoverPlay, p.hoverNext, p.progX, p.progY, p.pBarX, p.pBarTop,
                p.pBarW, p.filledW, p.hoverProgress, p.accent, p.waveX, p.waveY, p.waveProgress, p.buttonEffect,
                p.x, p.y, p.hudW, p.hudH, 8f);

            paintIcon(canvas, PREV_ICON, p.btnPrevX, p.btnY, p.iconSz);
            paintIcon(canvas, p.playPauseIcon, p.btnPlayX, p.btnY, p.iconSz);
            paintIcon(canvas, NEXT_ICON, p.btnNextX, p.btnY, p.iconSz);
        }

        if (p.showOutline) {
            paintOutline(canvas, p.outlineX, p.outlineY, p.outlineW, p.outlineH, p.outlineColor);
        }
    }

    private void paintIcon(Canvas canvas, Identifier id, double x, double y, int size) {
        SkiaPipRenderer r = SkiaPipRenderer.ACTIVE;
        Image img = r != null ? r.borrowTexture(id) : null;
        if (img == null) return;
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            canvas.drawImageRect(img, Rect.makeXYWH(0, 0, img.getWidth(), img.getHeight()),
                Rect.makeXYWH((float) x, (float) y, size, size), SamplingMode.LINEAR, paint, true);
        }
    }

    private void paintTitleAuthor(Canvas canvas, double x, double y, String title, String author, Color accent, boolean bloomPlus, boolean bloom) {
        if (skiaFontTitle == null || skiaFontAuthor == null) return;
        if (bloomPlus) {
            paintTextWithBloom(canvas, x, y, title, author, accent);
            return;
        }
        float titleY = (float) y + 16f, authorY = (float) y + 29f;
        try (Paint tp = new Paint()) {
            tp.setAntiAlias(true);
            tp.setColor(0xFFFFFFFF);
            canvas.drawString(title, (float) x, titleY, skiaFontTitle, tp);
            tp.setColor(accent.getRGB());
            canvas.drawString(author, (float) x, authorY, skiaFontAuthor, tp);
        }
        if (bloom) {
            int bloomAlpha = 60;
            int titleBloom = (bloomAlpha << 24) | 0xFFFFFF;
            int artistBloom = (bloomAlpha << 24) | (accent.getRGB() & 0xFFFFFF);
            int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            try (Paint p = new Paint()) {
                p.setAntiAlias(true);
                for (int[] off : offsets) {
                    p.setColor(titleBloom);
                    canvas.drawString(title, (float) x + off[0], titleY + off[1], skiaFontTitle, p);
                    p.setColor(artistBloom);
                    canvas.drawString(author, (float) x + off[0], authorY + off[1], skiaFontAuthor, p);
                }
            }
        }
    }

    private void paintCircularGlow(Canvas canvas, double cx, double cy, float radius, Color accent) {
        if (accent == null) return;
        try (Paint glowPaint = new Paint();
             ImageFilter dropShadow = ImageFilter.makeDropShadow(0, 0, radius * 0.4f, radius * 0.4f, accent.getRGB())) {
            glowPaint.setColor(accent.getRGB());
            glowPaint.setImageFilter(dropShadow);
            glowPaint.setAntiAlias(true);
            canvas.drawCircle((float) cx, (float) cy, radius * 0.95f, glowPaint);
        }
    }

    private void paintThumbnail(Canvas canvas, boolean useDisk, Identifier activeThumbId,
                                int tx, int ty, int tw, int th, float diskRotationDeg) {
        SkiaPipRenderer r = SkiaPipRenderer.ACTIVE;
        if (activeThumbId != null && r != null) {
            Image thumbImg = r.borrowTexture(activeThumbId);
            if (thumbImg == null) return;
            Rect srcFull = Rect.makeXYWH(0, 0, thumbImg.getWidth(), thumbImg.getHeight());

            if (useDisk) {
                float centerX = tx + tw / 2.0f, centerY = ty + th / 2.0f;
                Image vinylImg = r.borrowTexture(VINYL_TEX);

                canvas.save();
                canvas.translate(centerX, centerY);
                canvas.rotate(diskRotationDeg);
                canvas.translate(-centerX, -centerY);
                try (Paint imgPaint = new Paint()) {
                    imgPaint.setAntiAlias(true);
                    if (vinylImg != null) {
                        Rect vSrc = Rect.makeXYWH(0, 0, vinylImg.getWidth(), vinylImg.getHeight());
                        canvas.drawImageRect(vinylImg, vSrc, Rect.makeXYWH(tx, ty, tw, th), SamplingMode.LINEAR, imgPaint, true);
                    }
                    float coreScale = 0.42f;
                    int coreW = (int)(tw * coreScale), coreH = (int)(th * coreScale);
                    Rect coreDst = Rect.makeXYWH(tx + (tw - coreW) / 2f, ty + (th - coreH) / 2f, coreW, coreH);
                    canvas.drawImageRect(thumbImg, srcFull, coreDst, SamplingMode.LINEAR, imgPaint, true);
                }
                canvas.restore();

                float centerDot = Math.max(1, tw * 0.04f), centerInner = Math.max(1, tw * 0.02f);
                try (Paint dotPaint = new Paint()) {
                    dotPaint.setColor(0xFF000000);
                    canvas.drawRect(Rect.makeXYWH(centerX - centerDot, centerY - centerDot, centerDot * 2, centerDot * 2), dotPaint);
                    dotPaint.setColor(0xFF444444);
                    canvas.drawRect(Rect.makeXYWH(centerX - centerInner, centerY - centerInner, centerInner * 2, centerInner * 2), dotPaint);
                }
            } else {
                try (Paint imgPaint = new Paint()) {
                    imgPaint.setAntiAlias(true);
                    canvas.drawImageRect(thumbImg, srcFull, Rect.makeXYWH(tx, ty, tw, th), SamplingMode.LINEAR, imgPaint, true);
                }
                try (Paint border = new Paint()) {
                    border.setColor(new Color(255, 255, 255, 20).getRGB());
                    canvas.drawRect(Rect.makeXYWH(tx, ty, tw, 1), border);
                    canvas.drawRect(Rect.makeXYWH(tx, ty + th - 1, tw, 1), border);
                    canvas.drawRect(Rect.makeXYWH(tx, ty, 1, th), border);
                    canvas.drawRect(Rect.makeXYWH(tx + tw - 1, ty, 1, th), border);
                }
            }
        } else {
            try (Paint bg = new Paint()) {
                bg.setColor(new Color(30, 30, 30, 180).getRGB());
                canvas.drawRect(Rect.makeXYWH(tx, ty, tw, th), bg);
            }
            if (skiaFontBody != null) {
                String note = "♪";
                float nw = skiaFontBody.measureTextWidth(note);
                try (Paint notePaint = new Paint()) {
                    notePaint.setColor(new Color(80, 80, 80, 200).getRGB());
                    notePaint.setAntiAlias(true);
                    canvas.drawString(note, tx + (tw - nw) / 2f, ty + (th + 8) / 2f, skiaFontBody, notePaint);
                }
            }
        }
    }

    private void paintBars(Canvas canvas, double contentX, double y, double contentW) {
        boolean spotifyConnected = SpotifyIntegration.isSpotifyConnected;
        float audioAmp;
        if (spotifyConnected) {
            if (SpotifyIntegration.isSpotifyPlaying) {
                // Spotify doesn't expose real-time audio — simulate a lively amplitude using oscillators.
                long t = System.currentTimeMillis();
                audioAmp = (float)(0.35 + 0.35 * Math.abs(Math.sin(t / 650.0))
                                       + 0.30 * Math.abs(Math.cos(t / 420.0 + 0.8)));
                audioAmp = Math.min(1.0f, audioAmp);
            } else {
                audioAmp = 0f;
            }
        } else {
            audioAmp = PlayMusic.currentAmplitude;
        }
        if (audioAmp > smoothedAmp) smoothedAmp += (audioAmp - smoothedAmp) * 0.9f;
        else                        smoothedAmp += (audioAmp - smoothedAmp) * 0.08f;
        long  tick     = System.currentTimeMillis();

        float barW     = (float)((contentW - 10) / BAR_COUNT - 1.0f);
        if (barW < 1.0f) barW = 1.0f;

        int   alphaVal = (int) Math.max(0, Math.min(255, barAlpha.getValue()));
        int   barColor = new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), alphaVal).getRGB();

        float maxBarH  = HUD_HEIGHT - 22f;

        int fixedBottom = (int)(y + HUD_HEIGHT - 14);

        for (int i = 0; i < BAR_COUNT; i++) {
            double combined = Math.abs(Math.sin(tick / (60.0 + i * 5.5) + i) + Math.cos(tick / (90.0 - i * 4.0) - i * 0.5)) * 0.4 + 0.6;
            double bell    = Math.sin(Math.PI * (i / (double)(BAR_COUNT - 1)));
            float  targetH = (float)(2.0 + combined * maxBarH * bell * smoothedAmp * 0.65);
            if (targetH > maxBarH) targetH = maxBarH;
            boolean barsPaused = spotifyConnected ? !SpotifyIntegration.isSpotifyPlaying : PlayMusic.isPlayerPaused();
            if (barsPaused) targetH = 2.0f;

            barHeights[i] += (targetH - barHeights[i]) * 0.7f;

            float bx  = (float)(contentX + i * (barW + 1.0f));
            float by  = (float)(y + HUD_HEIGHT - 14 - barHeights[i]);
            float bh  = fixedBottom - by;

            if (gradientBars.getValue()) {
                int topColor = new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), Math.max(0, alphaVal - 150)).getRGB();
                try (Shader grad = Shader.makeLinearGradient(bx, by, bx, fixedBottom, new int[]{ topColor, barColor }, null);
                     Paint p = new Paint()) {
                    p.setShader(grad);
                    canvas.drawRect(Rect.makeXYWH(bx, by, barW, bh), p);
                }
            } else {
                try (Paint p = new Paint()) {
                    p.setColor(barColor);
                    canvas.drawRect(Rect.makeXYWH(bx, by, barW, bh), p);
                }
            }
        }
    }

    private void renderProgress(GuiGraphicsExtractor context, AudioTrack track, double contentX, double y, double contentW) {
        if (track == null) return;
        double progress = (dragPreviewRatio >= 0)
            ? dragPreviewRatio
            : (double) track.getPosition() / track.getDuration();
        if (dragPreviewRatio < 0 && track.getState() == AudioTrackState.FINISHED) progress = 1.0;
        int    pBarW    = (int)(contentW - 10); int    filledW  = Math.max(3, (int)(pBarW * progress));
        int    barTop   = (int)(y + HUD_HEIGHT - 10); int    barBot   = (int)(y + HUD_HEIGHT - 6);
        context.fill((int)contentX, barTop, (int)contentX + pBarW, barBot, new Color(0, 0, 0, 120).getRGB());
        int pColor = hoverProgress ? 0xFFFFFFFF : accentColor.getRGB();
        context.fill((int)contentX, barTop, (int)contentX + filledW, barBot, pColor);
    }

    /**
     * Truncates {@code text} so it fits {@code maxWidth} when drawn with {@code font},
     * appending an ellipsis. Must measure with the SAME font that will actually render
     * the string — measuring with one font (e.g. mc.font) and drawing with another
     * (e.g. a custom Skija TTF) is what caused long titles to get cut off mid-word
     * with no visible "…" instead of being clipped correctly.
     */
    private String clipText(Font font, String text, float maxWidth) {
        if (font.measureTextWidth(text) <= maxWidth) return text;
        String ellipsis = "…";
        while (text.length() > 1 && font.measureTextWidth(text + ellipsis) > maxWidth) text = text.substring(0, text.length() - 1);
        return text + ellipsis;
    }


    /** mc.font-metric clip, used only by the rare FontScreen popup (plain native text, no Skia there). */
    private String clipTextMc(Minecraft mc, String text, int maxWidth) {
        if (mc.font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        while (text.length() > 1 && mc.font.width(text + ellipsis) > maxWidth) text = text.substring(0, text.length() - 1);
        return text + ellipsis;
    }

    /** Plain native 1px outline, used only by the rare FontScreen popup. */
    private void fillOutline(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        context.fill(x - 1, y - 1, x + w + 1, y, color);
        context.fill(x - 1, y + h, x + w + 1, y + h + 1, color);
        context.fill(x - 1, y, x, y + h, color);
        context.fill(x + w, y, x + w + 1, y + h, color);
    }

    private void loadThumbnailAsync(String videoId) {
        if (thumbLoading) return;
        thumbLoading = true;
        pendingThumbBytesSquare = null; pendingThumbBytesCircle = null;   
        CompletableFuture.runAsync(() -> {
            try {
                String ytId = extractYoutubeId(videoId);
                byte[] imgBytes = fetchThumbBytes(ytId, "maxresdefault");
                if (imgBytes == null) imgBytes = fetchThumbBytes(ytId, "hqdefault");
                if (imgBytes == null) imgBytes = fetchThumbBytes(ytId, "mqdefault");
                if (imgBytes == null) { thumbLoading = false; return; }
                BufferedImage original = ImageIO.read(new ByteArrayInputStream(imgBytes));
                if (original == null) { thumbLoading = false; return; }
                int sw = original.getWidth(), sh = original.getHeight(), cropSz = Math.min(sw, sh);
                BufferedImage square = original.getSubimage((sw - cropSz) / 2, (sh - cropSz) / 2, cropSz, cropSz);
                
                BufferedImage resizedSquare = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D gSq = resizedSquare.createGraphics();
                gSq.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gSq.drawImage(square, 0, 0, 400, 400, null); gSq.dispose();
                ByteArrayOutputStream baosSq = new ByteArrayOutputStream(); ImageIO.write(resizedSquare, "PNG", baosSq);
                pendingThumbBytesSquare = baosSq.toByteArray();

                BufferedImage circleBuffer = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D gCirc = circleBuffer.createGraphics();
                gCirc.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                gCirc.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gCirc.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, 400, 400));
                gCirc.drawImage(square, 0, 0, 400, 400, null); gCirc.dispose();
                ByteArrayOutputStream baosCirc = new ByteArrayOutputStream(); ImageIO.write(circleBuffer, "PNG", baosCirc);
                pendingThumbBytesCircle = baosCirc.toByteArray();
            } catch (Exception e) { thumbLoading = false; }
        });
    }

    private byte[] fetchThumbBytes(String youtubeId, String quality) {
        try {
            URL url = new URL("https://img.youtube.com/vi/" + youtubeId + "/" + quality + ".jpg");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000); conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            InputStream in = conn.getInputStream(); byte[] data = in.readAllBytes();
            in.close(); conn.disconnect(); return data;
        } catch (Exception e) { return null; }
    }

    public void loadThumbnailFromUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return;
        thumbLoading = false;
        pendingThumbBytesSquare = null; pendingThumbBytesCircle = null;
        thumbLoading = true;
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000); conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (conn.getResponseCode() != 200) { conn.disconnect(); thumbLoading = false; return; }
                byte[] imgBytes = conn.getInputStream().readAllBytes();
                conn.disconnect();

                BufferedImage original = ImageIO.read(new ByteArrayInputStream(imgBytes));
                if (original == null) { thumbLoading = false; return; }
                int sw = original.getWidth(), sh = original.getHeight(), cropSz = Math.min(sw, sh);
                BufferedImage square = original.getSubimage((sw - cropSz) / 2, (sh - cropSz) / 2, cropSz, cropSz);

                BufferedImage resizedSquare = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D gSq = resizedSquare.createGraphics();
                gSq.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gSq.drawImage(square, 0, 0, 400, 400, null); gSq.dispose();
                ByteArrayOutputStream baosSq = new ByteArrayOutputStream();
                ImageIO.write(resizedSquare, "PNG", baosSq);
                pendingThumbBytesSquare = baosSq.toByteArray();

                BufferedImage circleBuffer = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D gCirc = circleBuffer.createGraphics();
                gCirc.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                gCirc.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gCirc.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, 400, 400));
                gCirc.drawImage(square, 0, 0, 400, 400, null); gCirc.dispose();
                ByteArrayOutputStream baosCirc = new ByteArrayOutputStream();
                ImageIO.write(circleBuffer, "PNG", baosCirc);
                pendingThumbBytesCircle = baosCirc.toByteArray();
            } catch (Exception e) { thumbLoading = false; }
        });
    }

    public void extractColorsFromUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000); conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (conn.getResponseCode() != 200) { conn.disconnect(); return; }
                byte[] imgBytes = conn.getInputStream().readAllBytes();
                conn.disconnect();

                BufferedImage original = ImageIO.read(new ByteArrayInputStream(imgBytes));
                if (original == null) return;
                int sw = original.getWidth(), sh = original.getHeight(), cropSz = Math.min(sw, sh);
                BufferedImage square = original.getSubimage((sw - cropSz) / 2, (sh - cropSz) / 2, cropSz, cropSz);

                long sumR = 0, sumG = 0, sumB = 0; int total = 0;
                for (int px = 0; px < square.getWidth(); px += 4) {
                    for (int py = 0; py < square.getHeight(); py += 4) {
                        int rgb = square.getRGB(px, py); if ((rgb & 0xFFFFFF) == 0) continue;
                        sumR += (rgb >> 16) & 0xFF; sumG += (rgb >> 8) & 0xFF; sumB += rgb & 0xFF; total++;
                    }
                }
                if (total == 0) total = 1;
                float[] hsb = Color.RGBtoHSB((int)(sumR / total), (int)(sumG / total), (int)(sumB / total), null);
                float sat = Math.min(1.0f, hsb[1]); if (sat < 0.4f) sat = 0.7f;
                int accentRGB = Color.HSBtoRGB(hsb[0], sat, 1.0f);
                this.targetAccent = new Color((accentRGB >> 16) & 0xFF, (accentRGB >> 8) & 0xFF, accentRGB & 0xFF, 255);
            } catch (Exception ignored) {}
        });
    }

    private void uploadPendingThumb(Minecraft mc) {
        if (pendingThumbBytesSquare == null || pendingThumbBytesCircle == null) return;
        byte[] bytesSq = pendingThumbBytesSquare; byte[] bytesCirc = pendingThumbBytesCircle;
        pendingThumbBytesSquare = null; pendingThumbBytesCircle = null;
        try {
            NativeImage imgSq = NativeImage.read(new ByteArrayInputStream(bytesSq));
            NativeImage imgCirc = NativeImage.read(new ByteArrayInputStream(bytesCirc));
            if (thumbTexSquare != null) { thumbTexSquare.close(); thumbTexSquare = null; }
            if (thumbSquareId != null) { mc.getTextureManager().release(thumbSquareId); thumbSquareId = null; }
            if (thumbTexCircle != null) { thumbTexCircle.close(); thumbTexCircle = null; }
            if (thumbCircleId != null) { mc.getTextureManager().release(thumbCircleId); thumbCircleId = null; }
            
            thumbGen++;
            thumbSquareId = Identifier.fromNamespaceAndPath("musichud", "thumb_sq_" + thumbGen);
            thumbTexSquare = new DynamicTexture(() -> "musichud_thumb_sq_" + thumbGen, imgSq);
            mc.getTextureManager().register(thumbSquareId, thumbTexSquare);
            
            thumbCircleId = Identifier.fromNamespaceAndPath("musichud", "thumb_circ_" + thumbGen);
            thumbTexCircle = new DynamicTexture(() -> "musichud_thumb_circ_" + thumbGen, imgCirc);
            mc.getTextureManager().register(thumbCircleId, thumbTexCircle);
        } catch (Exception e) {
            thumbSquareId = null; thumbTexSquare = null; thumbCircleId = null; thumbTexCircle = null;
        } finally { thumbLoading = false; }
    }

    private void extractCinematicColorsAsync(String videoId) {
        CompletableFuture.runAsync(() -> {
            try {
                String ytId = extractYoutubeId(videoId);
                byte[] imgBytes = fetchThumbBytes(ytId, "hqdefault");
                if (imgBytes == null) imgBytes = fetchThumbBytes(ytId, "mqdefault");
                if (imgBytes == null) return;
                BufferedImage original = ImageIO.read(new ByteArrayInputStream(imgBytes));
                if (original == null) return;
                int sw = original.getWidth(), sh = original.getHeight(), cropSz = Math.min(sw, sh);
                BufferedImage square = original.getSubimage((sw - cropSz) / 2, (sh - cropSz) / 2, cropSz, cropSz);
                long sumR = 0, sumG = 0, sumB = 0; int total = 0;
                for (int px = 0; px < square.getWidth();  px += 4) {
                    for (int py = 0; py < square.getHeight(); py += 4) {
                        int rgb = square.getRGB(px, py); if ((rgb & 0xFFFFFF) == 0) continue;
                        sumR += (rgb >> 16) & 0xFF; sumG += (rgb >>  8) & 0xFF; sumB +=  rgb & 0xFF; total++;
                    }
                }
                if (total == 0) total = 1;
                float[] hsb = Color.RGBtoHSB((int)(sumR/total), (int)(sumG/total), (int)(sumB/total), null);
                float sat = Math.min(1.0f, hsb[1]); if (sat < 0.4f) sat = 0.7f;
                int accentRGB = Color.HSBtoRGB(hsb[0], sat, 1.0f);
                this.targetAccent = new Color((accentRGB >> 16) & 0xFF, (accentRGB >> 8) & 0xFF, accentRGB & 0xFF, 255);
            } catch (Exception ignored) { this.targetAccent = new Color(0, 255, 150, 255); }
        });
    }

    private String extractYoutubeId(String videoId) {
        if (videoId.contains("v=")) { String s = videoId.split("v=")[1].split("&")[0]; return s.length() >= 11 ? s.substring(0, 11) : s; }
        if (videoId.contains("youtu.be/")) { String s = videoId.split("youtu.be/")[1].split("\\?")[0]; return s.length() >= 11 ? s.substring(0, 11) : s; }
        return videoId.length() >= 11 ? videoId.substring(0, 11) : videoId;
    }
    private void paintLiquidUI(Canvas canvas, double prevX, double playX, double nextX, double btnY, int iconSz,
                                  boolean hPrev, boolean hPlay, boolean hNext,
                                  double progX, double progY, double pBarX, double pBarTop,
                                  int pBarW, int filledW, boolean hProg, Color accent,
                                  double waveX, double waveY, float waveProg, ButtonEffect effect,
                                  double hudClipX, double hudClipY, double hudClipW, double hudClipH, float hudClipRadius) {
        if (pBarW > 0) {
            try (Paint barBg = new Paint()) {
                barBg.setColor(new Color(0, 0, 0, 120).getRGB());
                barBg.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH((float)pBarX, (float)pBarTop, (float)pBarW, 4f, 2f), barBg);
            }
            try (Paint barFill = new Paint()) {
                barFill.setColor(hProg ? 0xFFFFFFFF : accent.getRGB());
                barFill.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH((float)pBarX, (float)pBarTop, (float)filledW, 4f, 2f), barFill);
            }
        }

        canvas.save();
        canvas.clipRRect(RRect.makeXYWH((float)hudClipX, (float)hudClipY, (float)hudClipW, (float)hudClipH, hudClipRadius), ClipMode.INTERSECT, true);
        float maxRadius = (float) Math.hypot(hudClipW, hudClipH);

        if (effect == ButtonEffect.Topo) {
            drawTopoWave(canvas, (float)waveX, (float)waveY, waveProg, maxRadius);
        } else if (effect == ButtonEffect.Gradient) {
            drawGradientWave(canvas, (float)waveX, (float)waveY, waveProg, maxRadius, accent);
        } else {
            drawSplashWave(canvas, (float)waveX, (float)waveY, waveProg, maxRadius);
        }
        canvas.restore();

        float rBtn = iconSz / 2f + 4f;
        float cy = (float) btnY + (iconSz / 2f);
        if (hPrev) drawHoverGlow(canvas, (float)prevX + (iconSz / 2f), cy, rBtn);
        if (hPlay) drawHoverGlow(canvas, (float)playX + (iconSz / 2f), cy, rBtn);
        if (hNext) drawHoverGlow(canvas, (float)nextX + (iconSz / 2f), cy, rBtn);

        if (progX > 0) {
            drawProgressRefractionKnob(canvas, (float)progX, (float)progY, 5.5f, hProg);
        }
    }

    private void drawTopoWave(Canvas canvas, float cx, float cy, float progress, float maxRadius) {
        if (progress <= 0.0f || progress >= 1.0f) return;
        
        // Dùng Cubic Ease-Out để sóng bung ra nhẹ nhàng, mượt mắt hơn
        float easeProg = 1.0f - (float)Math.pow(1.0f - progress, 3.0);
        float fadeOut = 1.0f - progress;
        int alpha = (int)(255 * fadeOut);
        if (alpha <= 0) return;

        float baseRadius = easeProg * maxRadius;
        if (baseRadius <= 2.0f) return;

        int layers = 6;
        try (Paint wavePaint = new Paint();
             MaskFilter mf = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 1.5f)) { 

            wavePaint.setMode(PaintMode.STROKE);
            wavePaint.setStrokeWidth(1.2f); 
            wavePaint.setMaskFilter(mf);
            wavePaint.setAntiAlias(true);

            for (int l = 0; l < layers; l++) {
                float layerRadius = baseRadius - (l * maxRadius * 0.12f);
                if (layerRadius <= 2.0f) continue;

                int layerAlpha = (int)(alpha * (1.0f - (l * 0.12f)));
                if (layerAlpha <= 0) layerAlpha = 10;
                wavePaint.setColor(new Color(255, 255, 255, layerAlpha).getRGB());

                try (Path topoPath = buildTopoPath(cx, cy, layerRadius, progress)) {
                    canvas.drawPath(topoPath, wavePaint);
                }
            }
        }
    }

    private Path buildTopoPath(float cx, float cy, float radius, float progress) {
        final int SAMPLES = 40; 
        float[] xs = new float[SAMPLES];
        float[] ys = new float[SAMPLES];

        for (int i = 0; i < SAMPLES; i++) {
            double angle = (Math.PI * 2.0 * i) / SAMPLES;
            // FIX LỖI GIẬT FPS: Đưa progress vào hàm nhiễu để sóng uốn lượn liên tục thay vì tĩnh 1 chỗ
            double n = Math.sin(angle * 3.0 - progress * 5.0) * 0.15 + Math.cos(angle * 5.0 + progress * 3.0) * 0.08 + Math.sin(angle * 2.0 + 1.5) * 0.10;

            float r = (float)(radius * (1.0 + n));
            xs[i] = cx + (float)(Math.cos(angle) * r);
            ys[i] = cy + (float)(Math.sin(angle) * r);
        }

        PathBuilder builder = new PathBuilder();
        float startX = (xs[SAMPLES - 1] + xs[0]) / 2f;
        float startY = (ys[SAMPLES - 1] + ys[0]) / 2f;
        builder.moveTo(startX, startY);

        for (int i = 0; i < SAMPLES; i++) {
            int next = (i + 1) % SAMPLES;
            float midX = (xs[i] + xs[next]) / 2f;
            float midY = (ys[i] + ys[next]) / 2f;
            builder.quadTo(xs[i], ys[i], midX, midY);
        }
        builder.closePath();
        return builder.build();
    }

    // ─── HIỆU ỨNG 2: SPLASH WAVE (DẦU LOANG ĐÃ ĐƯỢC LÀM NHẠT ĐI) ───
    private void drawSplashWave(Canvas canvas, float cx, float cy, float progress, float maxRadius) {
        if (progress <= 0.0f || progress >= 1.0f) return;

        float easeProg = 1.0f - (float)Math.pow(1.0f - progress, 2.5);
        float fadeOut = 1.0f - easeProg;
        
        // GIẢM NHẸ ĐỘ ĐẬM CỦA SÓNG: Ép Alpha tối đa xuống 120 (thay vì 255/200 như trước)
        int alpha = (int)(120 * fadeOut);
        if (alpha <= 0) return;

        float baseRadius = easeProg * maxRadius;
        if (baseRadius <= 2.0f) return;

        int layers = 3;
        try (Paint wavePaint = new Paint();
             MaskFilter mf = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 6f + 4f * easeProg)) {

            wavePaint.setMode(PaintMode.FILL);
            wavePaint.setMaskFilter(mf);
            wavePaint.setAntiAlias(true);

            for (int l = 0; l < layers; l++) {
                float layerRadius = baseRadius * (1.0f - l * 0.16f);
                if (layerRadius <= 2.0f) continue;

                int layerAlpha = (l == 0) ? alpha : alpha / (l + 1);
                wavePaint.setColor(new Color(255, 255, 255, layerAlpha).getRGB());

                try (Path splash = buildSplashPath(cx, cy, layerRadius, easeProg, l * 7 + 13)) {
                    canvas.drawPath(splash, wavePaint);
                }
            }
        }
    }

    private Path buildSplashPath(float cx, float cy, float radius, float easeProg, int seed) {
        final int SAMPLES = 14;
        float[] xs = new float[SAMPLES];
        float[] ys = new float[SAMPLES];

        for (int i = 0; i < SAMPLES; i++) {
            double angle = (Math.PI * 2.0 * i) / SAMPLES;
            double n = 0;
            n += Math.sin(angle * 3.0 + seed * 0.9) * 0.20;
            n += Math.sin(angle * 5.0 + seed * 1.7) * 0.12;
            n += Math.sin(angle * 2.0 + seed * 0.4) * 0.10;

            double directional = Math.cos(angle) * 0.22 * easeProg;
            double rFactor = 1.0 + n + directional;
            if (rFactor < 0.35) rFactor = 0.35; 

            float r = (float)(radius * rFactor);
            xs[i] = cx + (float)(Math.cos(angle) * r);
            ys[i] = cy + (float)(Math.sin(angle) * r * 0.78f); 
        }

        PathBuilder builder = new PathBuilder();
        float startX = (xs[SAMPLES - 1] + xs[0]) / 2f;
        float startY = (ys[SAMPLES - 1] + ys[0]) / 2f;
        builder.moveTo(startX, startY);

        for (int i = 0; i < SAMPLES; i++) {
            int next = (i + 1) % SAMPLES;
            float midX = (xs[i] + xs[next]) / 2f;
            float midY = (ys[i] + ys[next]) / 2f;
            builder.quadTo(xs[i], ys[i], midX, midY);
        }
        builder.closePath();
        return builder.build();
    }

    // NÚM TIẾN TRÌNH VÀ HOVER GLOW
    private void drawProgressRefractionKnob(Canvas canvas, float cx, float cy, float radius, boolean hover) {
        float smallR = 4.5f;
        float glowSigma = hover ? 6f : 3f;
        int glowColor = new Color(255, 255, 255, hover ? 255 : 180).getRGB();
        try (Paint glow = new Paint();
             ImageFilter glowFilter = ImageFilter.makeDropShadowOnly(0, 0, glowSigma, glowSigma, glowColor)) {
            glow.setImageFilter(glowFilter);
            glow.setAntiAlias(true);
            canvas.drawCircle(cx, cy, smallR, glow);
        }
        try (Paint fill = new Paint()) {
            fill.setColor(0xFFFFFFFF);
            fill.setAntiAlias(true);
            canvas.drawCircle(cx, cy, smallR, fill);
        }
    }

    private void drawHoverGlow(Canvas canvas, float cx, float cy, float radius) {
        try (Paint glow = new Paint(); MaskFilter mf = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 7f)) {
            glow.setColor(new Color(255, 255, 255, 180).getRGB()); 
            glow.setMaskFilter(mf);
            glow.setAntiAlias(true);
            canvas.drawCircle(cx, cy, radius, glow);
        }
    }


    private Font skiaFontTitle;
    private Font skiaFontAuthor;
    private float lastTitleSize = -1f;
    private float lastAuthorSize = -1f;
    private long lastFontModifiedTime = -1L;

    private void paintTextWithBloom(Canvas canvas, double x, double y, String title, String author, Color accent) {
        if (skiaFontTitle == null || skiaFontAuthor == null) return;
        try (Paint textPaint = new Paint();
             Paint bloomPaint = new Paint();
             ImageFilter blurTitle = ImageFilter.makeDropShadowOnly(0, 0, 6f, 6f, 0xFFFFFFFF)) {

            textPaint.setAntiAlias(true);
            bloomPaint.setAntiAlias(true);

            bloomPaint.setImageFilter(blurTitle);
            canvas.drawString(title, (float) x, (float) y + 16f, skiaFontTitle, bloomPaint);
            textPaint.setColor(0xFFFFFFFF);
            canvas.drawString(title, (float) x, (float) y + 16f, skiaFontTitle, textPaint);

            try (ImageFilter blurAuthor = ImageFilter.makeDropShadowOnly(0, 0, 6f, 6f, accent.getRGB())) {
                bloomPaint.setImageFilter(blurAuthor);
                canvas.drawString(author, (float) x, (float) y + 29f, skiaFontAuthor, bloomPaint);
                textPaint.setColor(accent.getRGB());
                canvas.drawString(author, (float) x, (float) y + 29f, skiaFontAuthor, textPaint);
            }
        }
    }
    // ─── HIỆU ỨNG 3: GRADIENT 3D PAPER-CUT ───
    private void drawGradientWave(Canvas canvas, float cx, float cy, float progress, float maxRadius, Color accent) {
        if (progress <= 0.0f || progress >= 1.0f) return;

        float easeProg = 1.0f - (float)Math.pow(1.0f - progress, 2.5);
        float fadeOut = 1.0f - progress;
        int baseAlpha = (int)(255 * fadeOut);
        if (baseAlpha <= 0) return;

        float baseRadius = easeProg * maxRadius;
        if (baseRadius <= 2.0f) return;

        int layers = 5; 

        for (int l = 0; l < layers; l++) {
            float layerRadius = baseRadius - (l * maxRadius * 0.16f);
            if (layerRadius <= 2.0f) continue;

            float ratio = (float) l / (layers - 1); 
            
            int r = (int)(255 + (accent.getRed() - 255) * ratio);
            int g = (int)(255 + (accent.getGreen() - 255) * ratio);
            int b = (int)(255 + (accent.getBlue() - 255) * ratio);
            int a = (int)(baseAlpha * (0.3f + 0.7f * ratio));

            try (Paint wavePaint = new Paint()) {
                wavePaint.setMode(PaintMode.FILL); 
                wavePaint.setColor(new Color(r, g, b, Math.max(0, Math.min(255, a))).getRGB());
                wavePaint.setAntiAlias(true);

                int shadowAlpha = (int)(120 * fadeOut); 
                try (ImageFilter shadow = ImageFilter.makeDropShadow(0f, 4f, 10f, 10f, new Color(0, 0, 0, shadowAlpha).getRGB())) {
                    wavePaint.setImageFilter(shadow);
                    
                    try (io.github.humbleui.skija.Path path = buildTopoPath(cx, cy, layerRadius, progress)) {
                        canvas.drawPath(path, wavePaint);
                    }
                }
            }
        }
    }
    // ─── CLASS MÀN HÌNH CHỌN FONT ĐỘC LẬP (ĐÃ BYPASS LỖI MOUSECLICKED) ───
    public class FontScreen extends net.minecraft.client.gui.screens.Screen {
        private boolean previousHudHidden = false;
        private boolean wasMouseDownFont = false; // Biến ảo để bắt sự kiện click

        public FontScreen() {
            super(net.minecraft.network.chat.Component.literal("Music Fonts"));
        }

        @Override
        protected void init() {
            previousHudHidden = minecraft.options.hideGui;
            minecraft.options.hideGui = true; 
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui && !(mc.screen instanceof FontScreen)) return;
            context.fill(0, 0, this.width, this.height, 0x66000000);
            
            int winX = (this.width - 180) / 2;
            int winY = (this.height - 280) / 2;

            // Plain native fill (no Skia) — this settings popup is rare/non-perf-critical,
            // and GPU-Skija during extraction is exactly what crashes (see SkijaBackdropBlur).
            context.fill(winX, winY, winX + 180, winY + 280, new Color(15, 15, 15, 210).getRGB());
            context.fill(winX, winY, winX + 180, winY + 1, 0x3CFFFFFF);
            context.fill(winX, winY + 280, winX + 180, winY + 281, 0x3CFFFFFF);
            context.fill(winX, winY, winX + 1, winY + 280, 0x3CFFFFFF);
            context.fill(winX + 179, winY, winX + 180, winY + 280, 0x3CFFFFFF);

            String titleText = "Music Fonts";
            int titleW = minecraft.font.width(titleText);
            context.text(minecraft.font, titleText, winX + (180 - titleW) / 2, winY + 10, 0xFFFFFFFF, true);

            context.fill(winX + 10, winY + 26, winX + 170, winY + 236, 0x55000000);

            java.util.List<java.io.File> fontsCopy;
            synchronized(MusicHUD.this) { fontsCopy = new java.util.ArrayList<>(availableFonts); }

            int itemH = 15;
            int startY = winY + 28;
            for (int i = 0; i < fontsCopy.size() && i < 13; i++) {
                java.io.File f = fontsCopy.get(i);
                int itemY = startY + i * itemH;
                String name = f.getName().substring(0, f.getName().lastIndexOf('.'));
                
                boolean isSelected = (activeFontFile != null && activeFontFile.getAbsolutePath().equals(f.getAbsolutePath()));
                boolean isHovered = mouseX >= winX + 10 && mouseX <= winX + 170 && mouseY >= itemY && mouseY < itemY + itemH;

                if (isSelected) {
                    context.fill(winX + 12, itemY, winX + 168, itemY + itemH - 1, new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 90).getRGB());
                } else if (isHovered) {
                    context.fill(winX + 12, itemY, winX + 168, itemY + itemH - 1, 0x33FFFFFF);
                }

                String renderedName = clipTextMc(minecraft, name, 145);
                context.text(minecraft.font, renderedName, winX + 16, itemY + 3, isSelected ? 0xFFFFFFFF : 0xFFDDDDDD, true);
            }

            boolean hoverAdd = mouseX >= winX + 15 && mouseX <= winX + 75 && mouseY >= winY + 246 && mouseY <= winY + 266;
            context.fill(winX + 15, winY + 246, winX + 75, winY + 266, hoverAdd ? 0xFF444444 : 0xFF222222);
            fillOutline(context, winX + 15, winY + 246, 60, 20, 0x33FFFFFF);
            context.text(minecraft.font, "Add", winX + 15 + (60 - minecraft.font.width("Add")) / 2, winY + 246 + 6, 0xFFFFFFFF, true);

            boolean hoverClose = mouseX >= winX + 105 && mouseX <= winX + 165 && mouseY >= winY + 246 && mouseY <= winY + 266;
            context.fill(winX + 105, winY + 246, winX + 165, winY + 266, hoverClose ? 0xFF444444 : 0xFF222222);
            fillOutline(context, winX + 105, winY + 246, 60, 20, 0x33FFFFFF);
            context.text(minecraft.font, "Close", winX + 105 + (60 - minecraft.font.width("Close")) / 2, winY + 246 + 6, 0xFFFFFFFF, true);

            // ─── TÍCH HỢP BẮT CLICK CHUỘT TRỰC TIẾP TRONG RENDER ───
            boolean mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean justPressed = mouseDown && !wasMouseDownFont;
            wasMouseDownFont = mouseDown;

            if (justPressed) {
                java.io.File musicFontDir = new java.io.File(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "boze/musicfont");

                if (hoverAdd) {
                    try {
                        if (!musicFontDir.exists()) musicFontDir.mkdirs();
                        net.minecraft.util.Util.getPlatform().openFile(musicFontDir);
                    } catch (Exception ignored) {}
                } 
                else if (hoverClose) {
                    this.onClose();
                } 
                else if (mouseX >= winX + 10 && mouseX <= winX + 170 && mouseY >= winY + 26 && mouseY <= winY + 236) {
                    int clickedIdx = (int) ((mouseY - (winY + 28)) / 15);
                    synchronized (MusicHUD.this) {
                        if (clickedIdx >= 0 && clickedIdx < availableFonts.size() && clickedIdx < 13) {
                            activeFontFile = availableFonts.get(clickedIdx);
                            lastFontModifiedTime = -1L; // Cập nhật nóng
                            // LƯU LẠI FONT ĐỂ DÙNG CHO CÁC LẦN VÀO GAME SAU
                            try {
                                java.io.File saveFile = new java.io.File(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "boze/musicfont_save.txt");
                                java.nio.file.Files.writeString(saveFile.toPath(), activeFontFile.getAbsolutePath());
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            super.extractRenderState(context, mouseX, mouseY, delta);
        }

        @Override
        public void onClose() {
            minecraft.options.hideGui = previousHudHidden;
            openFontUi.setValue(false);
            super.onClose();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}