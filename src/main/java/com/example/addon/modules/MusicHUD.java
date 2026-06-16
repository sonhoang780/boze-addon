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
import meteordevelopment.orbit.EventHandler;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.option.ModeOption;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import io.github.humbleui.skija.*;
import io.github.humbleui.types.*;

public class MusicHUD extends AddonModule {
    public static final MusicHUD INSTANCE = new MusicHUD();
    public boolean active = false;

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
    public final SliderOption posX     = new SliderOption(this, "X Position", "Horizontal position.",                 10.0,  0.0, 2000.0, 1.0);
    public final SliderOption posY     = new SliderOption(this, "Y Position", "Vertical position.",                   10.0,  0.0, 1000.0, 1.0);
    public final SliderOption barAlpha = new SliderOption(this, "Bar Alpha",  "Transparency of the visualizer bars (0-255).", 80.0,  0.0,  255.0, 1.0);
    public final ToggleOption openFontUi = new ToggleOption(this, "Add Font", "", false);
    public final ToggleOption gradientBars = new ToggleOption(this, "Gradient Bars", "", true);
    public final SliderOption blurIntensity = new SliderOption(this, "Blur Intensity", "Intensity of the blur effect.", 15.0, 0.0, 50.0, 1.0);
    public final SliderOption titleFontSize = new SliderOption(this, "Title Font Size", "Size of the track title font (TextBloomPlus only)", 13.0, 5.0, 30.0, 0.5);
    public final SliderOption authorFontSize = new SliderOption(this, "Author Font Size", "Size of the artist name font (TextBloomPlus only)", 11.0, 5.0, 30.0, 0.5);
    public final ToggleOption textBloom = new ToggleOption(this, "Text Bloom", "Vanilla Text Bloom", false, () -> this.textBloomPlus == null || !this.textBloomPlus.getValue());
    public final ToggleOption textBloomPlus = new ToggleOption(this, "Text Bloom Plus", "", false, () -> this.textBloom == null || !this.textBloom.getValue());
    public final ToggleOption compactMode = new ToggleOption(this, "Compact Mode", "Collapse the HUD, limiting its height.", false);
    public final ToggleOption disk = new ToggleOption(this, "Disk", "Enable to show the spinning record.", true);
    public final ToggleOption ultraDisk = new ToggleOption(this, "Ultra Disk", "Only display the record, hide everything else.", false, () -> disk.getValue());
    public final SliderOption diskSize = new SliderOption(this, "Disk Size", "Size of the music disk (Ultra Disk).", 100.0, 30.0, 300.0, 1.0);
    
    public final ModeOption<LerpMode> lerpMode = new ModeOption<>(this, "Lerp Mode", "Lerp mode for the audio bars.", LerpMode.Full);
    public final ModeOption<ButtonEffect> buttonEffect = new ModeOption<>(this, "Button Effect", "Visual effect when clicking buttons.", ButtonEffect.Splash);
    private final java.util.List<java.io.File> availableFonts = new java.util.ArrayList<>();
    private java.io.File activeFontFile = null;
    private String lastFontPath = "";
    private static final int    BAR_COUNT  = 15;
    private static final int    THUMB_W    = 55;
    private static final int    THUMB_H    = 55;
    private static final int    HUD_HEIGHT = 70;
    private static final Identifier VINYL_TEX = Identifier.of("musichud", "vinyl.png");
    private static final Identifier PLAY_ICON = Identifier.of("musichud", "play.png");
    private static final Identifier PAUSE_ICON = Identifier.of("musichud", "pause-button.png");
    private static final Identifier PREV_ICON = Identifier.of("musichud", "previous.png");
    private static final Identifier NEXT_ICON = Identifier.of("musichud", "next-button.png");

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

    private boolean isDraggingWidth = false;
    private double manualTargetWidth = -1.0;

    private volatile byte[]   pendingThumbBytesSquare = null;
    private volatile byte[]   pendingThumbBytesCircle = null;
    private Identifier         thumbSquareId    = null;
    private Identifier         thumbCircleId    = null;
    private NativeImageBackedTexture thumbTexSquare   = null;
    private NativeImageBackedTexture thumbTexCircle   = null;
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

    private DirectContext skiaContext;

    private MusicHUD() {
        super("MusicHUD", "Music player HUD with Skia Rounded Corners & Glow.");
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            if (this.active) render(context);
        });
    }

    @Override public void onEnable()  { this.active = true; lastRenderTime = System.currentTimeMillis(); isFirstRender = true; }
    @Override public void onDisable() { this.active = false; }
    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (openFontUi.getValue()) {
            openFontUi.setValue(false); 
            mc.execute(() -> mc.setScreen(new FontScreen()));
        }
    }
    private void drawOutline(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x - 1, y - 1, x + w + 1, y, color);
        context.fill(x - 1, y + h, x + w + 1, y + h + 1, color);
        context.fill(x - 1, y, x, y + h, color);
        context.fill(x + w, y, x + w + 1, y + h, color);
    }

    private void drawSkiaBackground(double x, double y, double w, double h, float radius, Color accent, boolean enableGlow) {
        MinecraftClient mc = MinecraftClient.getInstance();
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT, 4);
        if (skiaContext == null) skiaContext = DirectContext.makeGL();
        skiaContext.resetAll(); 

        int mainFboId = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING);
        try (BackendRenderTarget rt = BackendRenderTarget.makeGL(
                mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(),
                0, 8, mainFboId, org.lwjgl.opengl.GL30C.GL_RGBA8);
             Surface surface = Surface.makeFromBackendRenderTarget(
                skiaContext, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB())) {
            
            Canvas canvas = surface.getCanvas();
            float scale = (float) mc.getWindow().getScaleFactor();
            canvas.scale(scale, scale);
            if (enableGlow && accent != null) {
                try (Paint glowPaint = new Paint();
                     MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.OUTER, 12f)) {
                    glowPaint.setColor(accent.getRGB());
                    glowPaint.setMaskFilter(blur);
                    glowPaint.setAntiAlias(true);
                    canvas.drawRRect(RRect.makeXYWH((float)x, (float)y, (float)w, (float)h, radius), glowPaint);
                }
            }
            
            // --- 3. VIỀN STROKE TRẮNG MỎNG ---
            try (Paint strokePaint = new Paint()) {
                strokePaint.setColor(new Color(255, 255, 255, 60).getRGB());
                strokePaint.setMode(PaintMode.STROKE);
                strokePaint.setStrokeWidth(1.0f);
                strokePaint.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH((float)x, (float)y, (float)w, (float)h, radius), strokePaint);
            }
            // --- 1. SKIA FROSTED GLASS (BACKDROP BLUR MƯỢT MÀ, BO GÓC HOÀN HẢO) ---
            canvas.save();
            // Cắt theo RRect để Blur không bao giờ bị lòi ra 4 góc
            canvas.clipRRect(RRect.makeXYWH((float)x, (float)y, (float)w, (float)h, radius), ClipMode.INTERSECT, true);
            
            float blurVal = (float)(double) blurIntensity.getValue();
            
            if (blurVal > 0) {
                try (ImageFilter backdropBlur = ImageFilter.makeBlur(blurVal, blurVal, FilterTileMode.CLAMP)) {
                    canvas.saveLayer(new SaveLayerRec(null, null, backdropBlur));
                    try (Paint bgPaint = new Paint()) {
                        bgPaint.setColor(new Color(15, 15, 15, 90).getRGB());
                        bgPaint.setAntiAlias(true);
                        canvas.drawRect(Rect.makeXYWH((float)x, (float)y, (float)w, (float)h), bgPaint);
                    }
                    canvas.restore();
                }
            } else {
                // Nếu kéo Blur về 0, chỉ vẽ màng đen để đỡ lag GPU
                try (Paint bgPaint = new Paint()) {
                    bgPaint.setColor(new Color(15, 15, 15, 90).getRGB());
                    bgPaint.setAntiAlias(true);
                    canvas.drawRect(Rect.makeXYWH((float)x, (float)y, (float)w, (float)h), bgPaint);
                }
            }

            skiaContext.flush();
        }
        
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
        org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA, org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_STENCIL_TEST);
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

    private double calcTargetWidth(MinecraftClient mc) {
        if (compactMode.getValue()) return THUMB_W + 10 + 130.0;
        double maxTextW;
        // Bắt chiều dài Font tự động co giãn theo Size của Skia
        if (textBloomPlus.getValue() && skiaFontTitle != null && skiaFontAuthor != null) {
            maxTextW = Math.max(skiaFontTitle.measureTextWidth(displayTitle), skiaFontAuthor.measureTextWidth(displayAuthor));
        } else {
            maxTextW = Math.max(mc.textRenderer.getWidth(displayTitle), mc.textRenderer.getWidth(displayAuthor));
        }
        return Math.ceil((THUMB_W + 10 + Math.max(160, maxTextW + 60)) / 10.0) * 10.0;
    }

    private void handleMouse(AudioTrack track, double hudX, double hudY, double hudW, double hudH) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen == null) {
            isDragging = wasMouseDown = hoverPrev = hoverPlay = hoverNext = hoverProgress = false;
            isDraggingWidth = false;
            dragPreviewRatio = -1;
            hudHoverStartTime = 0; return;
        }

        boolean useUltra = disk.getValue() && ultraDisk.getValue();
        double scale  = mc.getWindow().getScaleFactor();
        double mouseX = mc.mouse.getX() / scale;
        double mouseY = mc.mouse.getY() / scale;
        
        long win = mc.getWindow().getHandle();
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
        hoverProgress = track != null && mouseX >= pBarX && mouseX <= pBarX + pBarW && mouseY >= pBarY && mouseY <= pBarY + 10;

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
                if (hoverUltraDisk) PlayMusic.INSTANCE.togglePause.setValue(true);
            } else {
                if (hoverPlay) { PlayMusic.INSTANCE.togglePause.setValue(true); triggerWave(btnPlayX + 6, btnY + 6); }
                else if (hoverPrev) { PlayMusic.INSTANCE.previousBtn.setValue(true); triggerWave(btnPrevX + 6, btnY + 6); }
                else if (hoverNext) { PlayMusic.INSTANCE.nextBtn.setValue(true); triggerWave(btnNextX + 6, btnY + 6); }
                else if (hoverProgress && track != null) {
                    isDragging = true;
                    dragPreviewRatio = Math.max(0, Math.min(1, (mouseX - pBarX) / pBarW));
                }
            }
        }
        if (mouseDown && isDraggingWidth) {
            manualTargetWidth = Math.max(THUMB_W + 120.0, mouseX - hudX);
        }

        if (mouseDown && isDragging && track != null && !useUltra) {
            dragPreviewRatio = Math.max(0, Math.min(1, (mouseX - pBarX) / pBarW));
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastSeekSentAt >= 200) {
                PlayMusic.seekTo((long)(dragPreviewRatio * track.getDuration()));
                lastSeekSentAt = nowMs;
            }
        }
    
        boolean isHoveringTarget = useUltra ? hoverUltraDisk : (hoverHud && !isDragging && !hoverPlay && !hoverPrev && !hoverNext && !isDraggingWidth && !hoverEdge);
        if (mouseDown && isHoveringTarget) {
            if (hudHoverStartTime == 0) hudHoverStartTime = System.currentTimeMillis();
            else if (System.currentTimeMillis() - hudHoverStartTime >= 2500) {
                if (track != null) track.stop();
                isManuallyStopped = true;
                hudHoverStartTime = 0;
            }
        } else {
            hudHoverStartTime = 0;
        }
        if (justReleased) {
            if (isDragging && track != null && !useUltra) {
                seekToMouse(mouseX, pBarX, pBarW, track);
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
        MinecraftClient mc = MinecraftClient.getInstance();
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
    }

    private void render(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden && !(mc.currentScreen instanceof MusicHUD.FontScreen)) return;
        initSkiaFontsIfNeeded();
        long now = System.currentTimeMillis();
        long deltaMs = now - lastRenderTime;
        if (waveProgress < 1.0f) {
            waveProgress += (deltaMs / 600.0f); // Sóng lan tỏa trong 600ms
            if (waveProgress > 1.0f) waveProgress = 1.0f;
        }
        lastRenderTime = now;
        boolean useUltra = disk.getValue() && ultraDisk.getValue();

        if (pendingThumbBytesSquare != null && pendingThumbBytesCircle != null) uploadPendingThumb(mc);

        double x = posX.getValue();
        double y = posY.getValue();
        AudioTrack track = PlayMusic.getCurrentTrack();
        
        boolean isTrackPlaying = track != null && 
                                 track.getState() != AudioTrackState.INACTIVE && 
                                 track.getState() != AudioTrackState.STOPPING &&
                                 track.getState() != AudioTrackState.FINISHED;

        if (isFirstRender) {
            if (!isTrackPlaying) { animW = WIDTH_MIN; transPhase = 1; } 
            else { animW = calcTargetWidth(mc); transPhase = 2; }
            if (lerpMode.getValue() == LerpMode.Off && isTrackPlaying) animW = calcTargetWidth(mc);
            isFirstRender = false;
        }

        if (isTrackPlaying) isManuallyStopped = false; 
        if (isTrackPlaying && !PlayMusic.isPlayerPaused()) {
            smoothDiskRotation += deltaMs * 0.045f;
            smoothDiskRotation %= 360f;
        }

        accentColor = lerpColor(accentColor, targetAccent, 0.015f);

        double hudW = Math.max(animW, WIDTH_MIN);
        int currentThumbW = useUltra ? (int)(double)diskSize.getValue() : THUMB_W;
        int currentThumbH = useUltra ? (int)(double)diskSize.getValue() : THUMB_H;
        double hudH = useUltra ? currentThumbH : HUD_HEIGHT; 

        if (isManuallyStopped) {
            if (lerpMode.getValue() == LerpMode.Off) animW = WIDTH_MIN;
            else                                      animW = lerp(animW, WIDTH_MIN, LERP_CLOSE);
            
            if (!useUltra) {
                // Đổi Tint thành trong suốt hoàn toàn, nhường cho Skia vẽ Nền đen bo góc
                drawSkiaBackground(x, y, animW, HUD_HEIGHT, 8f, accentColor, false);
                context.drawText(mc.textRenderer, "Not playing", (int)x + 15, (int)y + 28, 0xFFFFFFFF, true);
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
            if (PlayMusic.playCount != currentPlayCount) {
                currentPlayCount = PlayMusic.playCount;
                
                if (lerpMode.getValue() == LerpMode.Off) {
                    transPhase = 0;
                    forceCloseForNewTrack = false;
                    animW = newTarget;
                } else {
                    forceCloseForNewTrack = true;
                    transPhase = 1; 
                }

                currentTrackId = track.getIdentifier();
                displayTitle   = track.getInfo().title;
                displayAuthor  = track.getInfo().author;
                extractCinematicColorsAsync(currentTrackId);
                loadThumbnailAsync(currentTrackId);
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

        if (!useUltra) {
            // Đổi Tint thành trong suốt hoàn toàn, nhường cho Skia vẽ Nền đen bo góc
            drawSkiaBackground(x, y, hudW, HUD_HEIGHT, 8f, accentColor, true);
        } else {
            drawSkiaCircularGlow(currentThumbX + currentThumbW / 2.0, currentThumbY + currentThumbH / 2.0, currentThumbW / 2.0f, accentColor);
        }

        renderThumbnail(context, track, currentThumbX, currentThumbY, currentThumbW, currentThumbH);

        // XÓA 100% CÁI DẢI MÀU BÊN TRÁI DO MINECRAFT VẼ VÀ ĐÃ NHƯỜNG SKIA VẼ HOẶC XÓA HOÀN TOÀN TÙY Ý MÀY

        if (!useUltra) {
            if (hudHoverStartTime > 0 && !HUDEditor.INSTANCE.active) {
                long held = System.currentTimeMillis() - hudHoverStartTime;
                float holdProgress = Math.min(1.0f, held / 2500.0f); 
                context.fill((int)x + 3, (int)(y + HUD_HEIGHT - 2), (int)(x + hudW * holdProgress - 3), (int)(y + HUD_HEIGHT), 0xFFFF3333); 
            }

            double contentX = x + THUMB_W + 10;
            double contentW = hudW - THUMB_W - 10 - 8;

            if (hudW > THUMB_W + 30) {
                String titleClipped  = clipText(mc, displayTitle,  (int)contentW - 30);
                String authorClipped = clipText(mc, displayAuthor, (int)contentW - 30);
                renderBars(context, track, contentX + 75, y, contentW - 75); 

                if (!textBloomPlus.getValue()) {
                    context.drawText(mc.textRenderer, titleClipped,  (int)contentX, (int)y + 8,  0xFFFFFFFF,        false);
                    context.drawText(mc.textRenderer, authorClipped, (int)contentX, (int)y + 20, accentColor.getRGB(), false);
                }


                if (textBloomPlus.getValue()) {
                    drawSkiaTextWithBloom(contentX, y, titleClipped, authorClipped, accentColor);
                } else {

                    org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
                    org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA, org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
                    org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);

                    if (textBloom.getValue()) {
                        int bloomAlpha = 60; 
                        int titleBloom = (bloomAlpha << 24) | 0xFFFFFF;
                        int artistBloom = (bloomAlpha << 24) | (accentColor.getRGB() & 0xFFFFFF);
                        
                        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                        for (int[] off : offsets) {
                            context.drawText(mc.textRenderer, titleClipped,  (int)contentX + off[0], (int)y + 8 + off[1],  titleBloom, false);
                            context.drawText(mc.textRenderer, authorClipped, (int)contentX + off[0], (int)y + 20 + off[1], artistBloom, false);
                        }
                    }
                }
                if (track != null) {
                    long s = track.getPosition() / 1000, ds = track.getDuration() / 1000;
                    if (!isTrackPlaying) s = ds;
                    String time = String.format("%02d:%02d / %02d:%02d", s/60, s%60, ds/60, ds%60);
                    int timeW = mc.textRenderer.getWidth(time);
                    context.drawText(mc.textRenderer, time, (int)(x + hudW - timeW - 8), (int)y + 35, 0xFFBBBBBB, false);
                }

                if (track != null) {
                    long s = track.getPosition() / 1000, ds = track.getDuration() / 1000;
                    if (!isTrackPlaying) s = ds;
                    String time = String.format("%02d:%02d / %02d:%02d", s/60, s%60, ds/60, ds%60);
                    int timeW = mc.textRenderer.getWidth(time);
                    context.drawText(mc.textRenderer, time, (int)(x + hudW - timeW - 8), (int)y + 35, 0xFFBBBBBB, false);
                }

                int iconSz = 12;
                int btnY = (int)y + 35;
                double btnPrevX = contentX + 2; 
                double btnPlayX = contentX + 25;
                double btnNextX = contentX + 48;

                // Tính tọa độ cho Progress Bar và Núm tròn giọt nước
                double progX = -1;
                double progY = y + HUD_HEIGHT - 8;
                int pBarW = (int)(contentW - 10);
                int filledW = 0;
                if (track != null) {
                    double progress = (dragPreviewRatio >= 0)
                        ? dragPreviewRatio
                        : (double) track.getPosition() / track.getDuration();
                    if (dragPreviewRatio < 0 && track.getState() == AudioTrackState.FINISHED) progress = 1.0;
                    filledW = Math.max(3, (int)(pBarW * progress));
                    progX = contentX + filledW;
                }

                // GIAO TOÀN QUYỀN VẼ SKIA Ở ĐÂY (TRUYỀN THÊM BIẾN SÓNG + KHUNG CLIP HUD VÀO)
                drawSkiaLiquidUI(btnPrevX, btnPlayX, btnNextX, btnY, iconSz, hoverPrev, hoverPlay, hoverNext, progX, progY, contentX, y + HUD_HEIGHT - 10, pBarW, filledW, hoverProgress, accentColor, waveX, waveY, waveProgress, x, y, hudW, HUD_HEIGHT, 8f);
                // VẼ ICON PNG LÊN TRÊN MẶT GIỌT NƯỚC
                context.drawTexture(RenderPipelines.GUI_TEXTURED, PREV_ICON, (int)btnPrevX, btnY, 0f, 0f, iconSz, iconSz, iconSz, iconSz);
                Identifier currentPlayIcon = (PlayMusic.isPlayerPaused() || !isTrackPlaying) ? PLAY_ICON : PAUSE_ICON;
                context.drawTexture(RenderPipelines.GUI_TEXTURED, currentPlayIcon, (int)btnPlayX, btnY, 0f, 0f, iconSz, iconSz, iconSz, iconSz);
                context.drawTexture(RenderPipelines.GUI_TEXTURED, NEXT_ICON, (int)btnNextX, btnY, 0f, 0f, iconSz, iconSz, iconSz, iconSz);
                }
            } else {
            if (hudHoverStartTime > 0 && !HUDEditor.INSTANCE.active) {
                long held = System.currentTimeMillis() - hudHoverStartTime;
                float holdProgress = Math.min(1.0f, held / 2500.0f); 
                context.fill((int)x, (int)(y + currentThumbH + 2), (int)(x + currentThumbW * holdProgress), (int)(y + currentThumbH + 4), 0xFFFF3333); 
            }
        }

        if (HUDEditor.INSTANCE.active) {
            double scale = mc.getWindow().getScaleFactor();
            double mx = mc.mouse.getX() / scale;
            double my = mc.mouse.getY() / scale;
            boolean mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

            if (mouseDown && !wasMouseDownEditor) {
                if (mx >= x && mx <= x + hudW && my >= y && my <= y + hudH) {
                    if (HUDEditor.draggingHUD.isEmpty() || HUDEditor.draggingHUD.equals("MusicHUD")) {
                        isDraggingHUD = true; HUDEditor.draggingHUD = "MusicHUD";
                        dragOffsetX = mx - x; dragOffsetY = my - y;
                    }
                }
            } else if (!mouseDown) {
                if (isDraggingHUD) HUDEditor.draggingHUD = "";
                isDraggingHUD = false;
            }

            if (isDraggingHUD && mouseDown) {
                x = mx - dragOffsetX; y = my - dragOffsetY;
                posX.setValue(x); posY.setValue(y);
                drawOutline(context, (int)x, (int)y, (int)hudW, (int)hudH, 0xFF00FF00);
            } else if (mx >= x && mx <= x + hudW && my >= y && my <= y + hudH) {
                drawOutline(context, (int)x, (int)y, (int)hudW, (int)hudH, 0xFFFFFF00);
            }
            wasMouseDownEditor = mouseDown;
        }
    }

    private void drawSkiaCircularGlow(double cx, double cy, float radius, Color accent) {
        if (accent == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT, 4);
        if (skiaContext == null) skiaContext = DirectContext.makeGL();
        skiaContext.resetAll(); 

        int fboId = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING);
        try (BackendRenderTarget rt = BackendRenderTarget.makeGL(
                mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(),
                0, 8, fboId, org.lwjgl.opengl.GL30C.GL_RGBA8);
             Surface surface = Surface.makeFromBackendRenderTarget(
                skiaContext, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB())) {
            
            Canvas canvas = surface.getCanvas();
            float scale = (float) mc.getWindow().getScaleFactor();
            canvas.scale(scale, scale);
            
            try (Paint glowPaint = new Paint();
                 ImageFilter dropShadow = ImageFilter.makeDropShadow(0, 0, radius * 0.4f, radius * 0.4f, accent.getRGB())) {
                glowPaint.setColor(accent.getRGB());
                glowPaint.setImageFilter(dropShadow);
                glowPaint.setAntiAlias(true);
                canvas.drawCircle((float)cx, (float)cy, radius * 0.95f, glowPaint);
            }
            skiaContext.flush();
        }
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
        org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA, org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_STENCIL_TEST);
    }

    private void renderThumbnail(DrawContext context, AudioTrack track, int tx, int ty, int tw, int th) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean useDisk = disk.getValue();
        Identifier activeThumbId = useDisk ? thumbCircleId : thumbSquareId;

        if (activeThumbId != null) {
            if (useDisk) {
                float centerX = tx + tw / 2.0f; float centerY = ty + th / 2.0f;
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(centerX, centerY);
                context.getMatrices().rotate((float) Math.toRadians(smoothDiskRotation)); 
                context.getMatrices().translate(-centerX, -centerY);
                context.drawTexture(RenderPipelines.GUI_TEXTURED, VINYL_TEX, tx, ty, 0f, 0f, tw, th, tw, th);
                float coreScale = 0.42f; int coreW = (int) (tw * coreScale), coreH = (int) (th * coreScale);
                context.drawTexture(RenderPipelines.GUI_TEXTURED, activeThumbId, tx + (tw - coreW) / 2, ty + (th - coreH) / 2, 0f, 0f, coreW, coreH, coreW, coreH);
                context.getMatrices().popMatrix();
                
                int centerDot = (int)Math.max(1, tw * 0.04); int centerInner = (int)Math.max(1, tw * 0.02);
                context.fill((int)centerX - centerDot, (int)centerY - centerDot, (int)centerX + centerDot, (int)centerY + centerDot, 0xFF000000);
                context.fill((int)centerX - centerInner, (int)centerY - centerInner, (int)centerX + centerInner, (int)centerY + centerInner, 0xFF444444);
            } else {
                context.drawTexture(RenderPipelines.GUI_TEXTURED, activeThumbId, tx, ty, 0f, 0f, tw, th, tw, th);
                int bc = new Color(255, 255, 255, 20).getRGB();
                context.fill(tx, ty, tx + tw, ty + 1, bc); context.fill(tx, ty + th - 1, tx + tw, ty + th, bc);
                context.fill(tx, ty, tx + 1, ty + th, bc); context.fill(tx + tw - 1, ty, tx + tw, ty + th, bc);
            }
        } else {
            context.fill(tx, ty, tx + tw, ty + th, new Color(30, 30, 30, 180).getRGB());
            String note = "♪"; int nw = mc.textRenderer.getWidth(note);
            context.drawText(mc.textRenderer, note, tx + (tw - nw) / 2, ty + (th - 8) / 2, new Color(80, 80, 80, 200).getRGB(), false);
        }
    }

    private void renderBars(DrawContext context, AudioTrack track, double contentX, double y, double contentW) {
        float audioAmp = PlayMusic.currentAmplitude;
        if (audioAmp > smoothedAmp) smoothedAmp += (audioAmp - smoothedAmp) * 0.9f;
        else                        smoothedAmp += (audioAmp - smoothedAmp) * 0.08f;
        long  tick     = System.currentTimeMillis();
        
        // Khôi phục lại độ rộng full size của thanh bars
        float barW     = (float)((contentW - 10) / BAR_COUNT - 1.0f);
        if (barW < 1.0f) barW = 1.0f;
        
        int   alphaVal = (int) Math.max(0, Math.min(255, barAlpha.getValue()));
        int   barColor = new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), alphaVal).getRGB();
        
        // Khôi phục lại chiều cao max chạm nóc
        float maxBarH  = HUD_HEIGHT - 22f; 
        
        // Vẫn giữ chốt đáy để chống lỗi giật (jitter) 1-pixel
        int fixedBottom = (int)(y + HUD_HEIGHT - 14);
        
        for (int i = 0; i < BAR_COUNT; i++) {
            double combined = Math.abs(Math.sin(tick / (60.0 + i * 5.5) + i) + Math.cos(tick / (90.0 - i * 4.0) - i * 0.5)) * 0.4 + 0.6;
            double bell    = Math.sin(Math.PI * (i / (double)(BAR_COUNT - 1)));
            float  targetH = (float)(2.0 + combined * maxBarH * bell * smoothedAmp * 0.65);
            if (targetH > maxBarH) targetH = maxBarH;
            if (PlayMusic.isPlayerPaused()) targetH = 2.0f;
            
            barHeights[i] += (targetH - barHeights[i]) * 0.7f;
            
            int bx  = (int)(contentX + i * (barW + 1.0f)); 
            int by  = (int)(y + HUD_HEIGHT - 14 - barHeights[i]);
            int bx2 = (int)(bx + barW);
            
            if (gradientBars.getValue()) {
                int topColor = new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), Math.max(0, alphaVal - 150)).getRGB();
                context.fillGradient(bx, by, bx2, fixedBottom, topColor, barColor);
            } else {
                context.fill(bx, by, bx2, fixedBottom, barColor);
            }
        }
    }

    private void renderProgress(DrawContext context, AudioTrack track, double contentX, double y, double contentW) {
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

    private String clipText(MinecraftClient mc, String text, int maxWidth) {
        if (mc.textRenderer.getWidth(text) <= maxWidth) return text;
        String ellipsis = "…";
        while (text.length() > 1 && mc.textRenderer.getWidth(text + ellipsis) > maxWidth) text = text.substring(0, text.length() - 1);
        return text + ellipsis;
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

    private void uploadPendingThumb(MinecraftClient mc) {
        if (pendingThumbBytesSquare == null || pendingThumbBytesCircle == null) return;
        byte[] bytesSq = pendingThumbBytesSquare; byte[] bytesCirc = pendingThumbBytesCircle;
        pendingThumbBytesSquare = null; pendingThumbBytesCircle = null;
        try {
            NativeImage imgSq = NativeImage.read(new ByteArrayInputStream(bytesSq));
            NativeImage imgCirc = NativeImage.read(new ByteArrayInputStream(bytesCirc));
            if (thumbTexSquare != null) { thumbTexSquare.close(); thumbTexSquare = null; }
            if (thumbSquareId != null) { mc.getTextureManager().destroyTexture(thumbSquareId); thumbSquareId = null; }
            if (thumbTexCircle != null) { thumbTexCircle.close(); thumbTexCircle = null; }
            if (thumbCircleId != null) { mc.getTextureManager().destroyTexture(thumbCircleId); thumbCircleId = null; }
            
            thumbGen++;
            thumbSquareId = Identifier.of("musichud", "thumb_sq_" + thumbGen);
            thumbTexSquare = new NativeImageBackedTexture(() -> "musichud_thumb_sq_" + thumbGen, imgSq);
            mc.getTextureManager().registerTexture(thumbSquareId, thumbTexSquare);
            
            thumbCircleId = Identifier.of("musichud", "thumb_circ_" + thumbGen);
            thumbTexCircle = new NativeImageBackedTexture(() -> "musichud_thumb_circ_" + thumbGen, imgCirc);
            mc.getTextureManager().registerTexture(thumbCircleId, thumbTexCircle);
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
    private void drawSkiaLiquidUI(double prevX, double playX, double nextX, double btnY, int iconSz, 
                                  boolean hPrev, boolean hPlay, boolean hNext, 
                                  double progX, double progY, double pBarX, double pBarTop, 
                                  int pBarW, int filledW, boolean hProg, Color accent,
                                  double waveX, double waveY, float waveProg,
                                  double hudClipX, double hudClipY, double hudClipW, double hudClipH, float hudClipRadius) {
        MinecraftClient mc = MinecraftClient.getInstance();
        
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT, 4);

        if (skiaContext == null) skiaContext = DirectContext.makeGL();
        skiaContext.resetAll();

        int fboId = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING);
        try (BackendRenderTarget rt = BackendRenderTarget.makeGL(
                mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(),
                0, 8, fboId, org.lwjgl.opengl.GL30C.GL_RGBA8);
             Surface surface = Surface.makeFromBackendRenderTarget(
                skiaContext, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB())) {
            
            Canvas canvas = surface.getCanvas();
            float scale = (float) mc.getWindow().getScaleFactor();
            canvas.scale(scale, scale);

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

            // ĐÃ THÊM LẠI IF BỊ THIẾU VÀ TÍCH HỢP ĐỦ 3 CHẾ ĐỘ

                canvas.save();
                canvas.clipRRect(RRect.makeXYWH((float)hudClipX, (float)hudClipY, (float)hudClipW, (float)hudClipH, hudClipRadius), ClipMode.INTERSECT, true);
                float maxRadius = (float) Math.hypot(hudClipW, hudClipH);
                
                ButtonEffect effect = buttonEffect.getValue();
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

            skiaContext.flush();
        }
        
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
        org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA, org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_STENCIL_TEST);

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

        Path path = new Path();
        float startX = (xs[SAMPLES - 1] + xs[0]) / 2f;
        float startY = (ys[SAMPLES - 1] + ys[0]) / 2f;
        path.moveTo(startX, startY);

        for (int i = 0; i < SAMPLES; i++) {
            int next = (i + 1) % SAMPLES;
            float midX = (xs[i] + xs[next]) / 2f;
            float midY = (ys[i] + ys[next]) / 2f;
            path.quadTo(xs[i], ys[i], midX, midY);
        }
        path.closePath();
        return path;
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

        Path path = new Path();
        float startX = (xs[SAMPLES - 1] + xs[0]) / 2f;
        float startY = (ys[SAMPLES - 1] + ys[0]) / 2f;
        path.moveTo(startX, startY);

        for (int i = 0; i < SAMPLES; i++) {
            int next = (i + 1) % SAMPLES;
            float midX = (xs[i] + xs[next]) / 2f;
            float midY = (ys[i] + ys[next]) / 2f;
            path.quadTo(xs[i], ys[i], midX, midY);
        }
        path.closePath();
        return path;
    }

    // NÚM TIẾN TRÌNH VÀ HOVER GLOW
    private void drawProgressRefractionKnob(Canvas canvas, float cx, float cy, float radius, boolean hover) {
        float smallR = 4.5f; 
        try (Paint glow = new Paint(); MaskFilter mf = MaskFilter.makeBlur(FilterBlurMode.OUTER, hover ? 6f : 3f)) {
            glow.setColor(new Color(255, 255, 255, hover ? 255 : 180).getRGB());
            glow.setMaskFilter(mf);
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

    private void drawSkiaTextWithBloom(double cx, double cy, String title, String author, Color accent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT, 4);
        if (skiaContext == null) skiaContext = DirectContext.makeGL();
        skiaContext.resetAll();

        int fboId = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING);
        try (BackendRenderTarget rt = BackendRenderTarget.makeGL(
                mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(),
                0, 8, fboId, org.lwjgl.opengl.GL30C.GL_RGBA8);
          Surface surface = Surface.makeFromBackendRenderTarget(
                skiaContext, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB())) { 
            Canvas canvas = surface.getCanvas();
            float scale = (float) mc.getWindow().getScaleFactor();
            canvas.scale(scale, scale);

            if (skiaFontTitle != null && skiaFontAuthor != null) {
                try (Paint textPaint = new Paint();
                     Paint bloomPaint = new Paint();
                     ImageFilter blurTitle = ImageFilter.makeDropShadowOnly(0, 0, 6f, 6f, 0xFFFFFFFF)) {
                    
                    textPaint.setAntiAlias(true);
                    bloomPaint.setAntiAlias(true);

                    bloomPaint.setImageFilter(blurTitle);
                    canvas.drawString(title, (float)cx, (float)cy + 16f, skiaFontTitle, bloomPaint);
                    textPaint.setColor(0xFFFFFFFF);
                    canvas.drawString(title, (float)cx, (float)cy + 16f, skiaFontTitle, textPaint);

                    try (ImageFilter blurAuthor = ImageFilter.makeDropShadowOnly(0, 0, 6f, 6f, accent.getRGB())) {
                        bloomPaint.setImageFilter(blurAuthor);
                        canvas.drawString(author, (float)cx, (float)cy + 29f, skiaFontAuthor, bloomPaint);
                        textPaint.setColor(accent.getRGB());
                        canvas.drawString(author, (float)cx, (float)cy + 29f, skiaFontAuthor, textPaint);
                    }
                }
            }
            skiaContext.flush();
        }
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
        org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA, org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_STENCIL_TEST);
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
    public class FontScreen extends net.minecraft.client.gui.screen.Screen {
        private boolean previousHudHidden = false;
        private boolean wasMouseDownFont = false; // Biến ảo để bắt sự kiện click

        public FontScreen() {
            super(net.minecraft.text.Text.literal("Music Fonts"));
        }

        @Override
        protected void init() {
            previousHudHidden = client.options.hudHidden;
            client.options.hudHidden = true; 
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden && !(mc.currentScreen instanceof FontScreen)) return;
            context.fill(0, 0, this.width, this.height, 0x66000000);
            
            int winX = (this.width - 180) / 2;
            int winY = (this.height - 280) / 2;

            drawSkiaBackground(winX, winY, 180, 280, 6f, accentColor, true);

            String titleText = "Music Fonts";
            int titleW = client.textRenderer.getWidth(titleText);
            context.drawText(client.textRenderer, titleText, winX + (180 - titleW) / 2, winY + 10, 0xFFFFFFFF, true);

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

                String renderedName = clipText(client, name, 145);
                context.drawText(client.textRenderer, renderedName, winX + 16, itemY + 3, isSelected ? 0xFFFFFFFF : 0xFFDDDDDD, true);
            }

            boolean hoverAdd = mouseX >= winX + 15 && mouseX <= winX + 75 && mouseY >= winY + 246 && mouseY <= winY + 266;
            context.fill(winX + 15, winY + 246, winX + 75, winY + 266, hoverAdd ? 0xFF444444 : 0xFF222222);
            drawOutline(context, winX + 15, winY + 246, 60, 20, 0x33FFFFFF);
            context.drawText(client.textRenderer, "Add", winX + 15 + (60 - client.textRenderer.getWidth("Add")) / 2, winY + 246 + 6, 0xFFFFFFFF, true);

            boolean hoverClose = mouseX >= winX + 105 && mouseX <= winX + 165 && mouseY >= winY + 246 && mouseY <= winY + 266;
            context.fill(winX + 105, winY + 246, winX + 165, winY + 266, hoverClose ? 0xFF444444 : 0xFF222222);
            drawOutline(context, winX + 105, winY + 246, 60, 20, 0x33FFFFFF);
            context.drawText(client.textRenderer, "Close", winX + 105 + (60 - client.textRenderer.getWidth("Close")) / 2, winY + 246 + 6, 0xFFFFFFFF, true);

            // ─── TÍCH HỢP BẮT CLICK CHUỘT TRỰC TIẾP TRONG RENDER ───
            boolean mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(client.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean justPressed = mouseDown && !wasMouseDownFont;
            wasMouseDownFont = mouseDown;

            if (justPressed) {
                java.io.File musicFontDir = new java.io.File(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "boze/musicfont");

                if (hoverAdd) {
                    try {
                        if (!musicFontDir.exists()) musicFontDir.mkdirs();
                        net.minecraft.util.Util.getOperatingSystem().open(musicFontDir);
                    } catch (Exception ignored) {}
                } 
                else if (hoverClose) {
                    this.close();
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

            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() {
            client.options.hudHidden = previousHudHidden;
            openFontUi.setValue(false);
            super.close();
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}