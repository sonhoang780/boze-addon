package com.example.addon.modules;

import com.example.addon.screens.CustomTitleScreen;
import com.example.addon.video.VideoPlayer;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import com.example.addon.screens.CachedSkiaTexture;

import io.github.humbleui.skija.*;
import io.github.humbleui.types.*;

import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LoadingScreen extends AddonModule {
    public static final LoadingScreen INSTANCE = new LoadingScreen();

    // Descriptions changed to English
    public final ToggleOption openBackgroundUI = new ToggleOption(this, "Background", "background menu", false);
    public final ToggleOption openIntroUI = new ToggleOption(this, "Intro", "intro menu", false);
    public final ToggleOption sound = new ToggleOption(this, "Sound", "Play audio from background video", true);

    public boolean active = false;
    public boolean introPlayed = false;

    // DEFAULT values. These change dynamically when you select a video in the UI.
    public String selectedBgName = "background.mp4";
    public String selectedIntroName = "intro.mp4";

    private CachedSkiaTexture panelTex;

    public LoadingScreen() {
        super("LoadingScreen", "Custom loading screen and main menu background video");
    }

    @Override
    public void onEnable() {
        active = true;
        introPlayed = false;
    }

    @Override
    public void onDisable() {
        active = false;
        VideoPlayer bg = CustomTitleScreen.activeBgVideo;
        if (bg != null) bg.setAudioEnabled(false);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof CustomTitleScreen) {
            mc.execute(() -> mc.setScreen(new TitleScreen()));
        }
    }

    @EventHandler
    private void onTick(EventTick.Pre e) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();

        if (openBackgroundUI.getValue()) {
            openBackgroundUI.setValue(false);
            mc.setScreen(new VideoSelectorScreen("background"));
        }
        if (openIntroUI.getValue()) {
            openIntroUI.setValue(false);
            mc.setScreen(new VideoSelectorScreen("intro"));
        }

        VideoPlayer bg = CustomTitleScreen.activeBgVideo;
        if (bg != null) bg.setAudioEnabled(sound.getValue());
    }

    /** GPU-direct panel chrome (glow + dark fill + stroke), composited via blit.
     *  Backdrop blur-of-the-world is handled by the screen's own extractBlurredBackground call. */
    private void drawSkiaPanel(GuiGraphicsExtractor context, double x, double y, double w, double h, float radius) {
        Minecraft mc = Minecraft.getInstance();
        float scale = (float) mc.getWindow().getGuiScale();
        float margin = 20f;
        int pw = Math.round((float)(w + margin * 2) * scale);
        int ph = Math.round((float)(h + margin * 2) * scale);
        if (pw <= 0 || ph <= 0) return;

        if (panelTex == null) panelTex = new CachedSkiaTexture("loadingscreen_panel");
        String key = pw + "x" + ph + "|" + radius;
        panelTex.render(pw, ph, key, canvas -> {
            canvas.scale(scale, scale);
            float fx = margin, fy = margin, fw = (float)w, fh = (float)h;

            try (Paint glowPaint = new Paint(); MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.OUTER, 14f)) {
                glowPaint.setColor(new Color(255, 255, 255, 200).getRGB());
                glowPaint.setMaskFilter(blur); glowPaint.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH(fx, fy, fw, fh, radius), glowPaint);
            }
            try (Paint bgPaint = new Paint()) {
                bgPaint.setColor(new Color(18, 18, 22, 225).getRGB()); bgPaint.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH(fx, fy, fw, fh, radius), bgPaint);
            }
            try (Paint strokePaint = new Paint()) {
                strokePaint.setColor(new Color(255, 255, 255, 70).getRGB());
                strokePaint.setMode(PaintMode.STROKE); strokePaint.setStrokeWidth(1.2f); strokePaint.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH(fx, fy, fw, fh, radius), strokePaint);
            }
        });
        panelTex.blit(context, (int)Math.round(x - margin), (int)Math.round(y - margin),
            (int)Math.round(w + margin * 2), (int)Math.round(h + margin * 2));
    }

    public class VideoSelectorScreen extends net.minecraft.client.gui.screens.Screen {
        private final String mode;
        private final List<File> availableVideos = new ArrayList<>();
        private File selectedFile = null;
        private boolean wasMouseDown = false;

        public VideoSelectorScreen(String mode) {
            super(net.minecraft.network.chat.Component.literal("Select " + mode));
            this.mode = mode;
        }

        @Override
        protected void init() {
            // DYNAMICALLY SCANS ALL MP4 FILES IN THE DIRECTORY
            File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/" + mode);
            if (!dir.exists()) dir.mkdirs();
            
            availableVideos.clear();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().toLowerCase().endsWith(".mp4")) {
                        availableVideos.add(f);
                    }
                }
            }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0x66000000);
            int winX = (this.width - 240) / 2; 
            int winY = (this.height - 300) / 2;

            context.fill(winX, winY, winX + 240, winY + 300, new Color(15, 15, 15, 210).getRGB());
            context.fill(winX, winY,       winX + 240, winY + 1,   0x3CFFFFFF);
            context.fill(winX, winY + 299, winX + 240, winY + 300, 0x3CFFFFFF);
            context.fill(winX, winY,       winX + 1,   winY + 300, 0x3CFFFFFF);
            context.fill(winX + 239, winY, winX + 240, winY + 300, 0x3CFFFFFF);

            String title = "Select " + (mode.equals("background") ? "Background" : "Intro");
            context.text(minecraft.font, title, winX + (240 - minecraft.font.width(title)) / 2, winY + 10, 0xFFFFFFFF, true);
            context.fill(winX + 10, winY + 26, winX + 230, winY + 256, 0x44000000);

            int itemH = 16; 
            int startY = winY + 28;
            for (int i = 0; i < availableVideos.size() && i < 14; i++) {
                File f = availableVideos.get(i); 
                int itemY = startY + i * itemH;
                boolean isSelected = (selectedFile != null && selectedFile.equals(f));
                boolean isHovered  = mouseX >= winX + 10 && mouseX <= winX + 230 && mouseY >= itemY && mouseY < itemY + itemH;

                if (isSelected) context.fill(winX + 12, itemY, winX + 228, itemY + itemH - 1, new Color(255, 255, 255, 70).getRGB());
                else if (isHovered) context.fill(winX + 12, itemY, winX + 228, itemY + itemH - 1, 0x33FFFFFF);

                context.text(minecraft.font, "🎥 " + f.getName(), winX + 16, itemY + 4, isSelected ? 0xFFFFFFFF : 0xFFCCCCCC, true);
            }

            boolean hoverOpen = mouseX >= winX + 20 && mouseX <= winX + 100 && mouseY >= winY + 266 && mouseY <= winY + 286;
            context.fill(winX + 20, winY + 266, winX + 100, winY + 286, selectedFile == null ? 0xFF1A1A1A : (hoverOpen ? 0xFF444444 : 0xFF222222));
            context.fill(winX + 20, winY + 266, winX + 100, winY + 267, 0x3CFFFFFF);
            context.fill(winX + 20, winY + 285, winX + 100, winY + 286, 0x3CFFFFFF);
            context.fill(winX + 20, winY + 266, winX + 21,  winY + 286, 0x3CFFFFFF);
            context.fill(winX + 99, winY + 266, winX + 100, winY + 286, 0x3CFFFFFF);
            context.text(minecraft.font, "Open", winX + 20 + (80 - minecraft.font.width("Open")) / 2, winY + 266 + 6, selectedFile != null ? 0xFFFFFFFF : 0xFF666666, true);

            boolean hoverClose = mouseX >= winX + 140 && mouseX <= winX + 220 && mouseY >= winY + 266 && mouseY <= winY + 286;
            context.fill(winX + 140, winY + 266, winX + 220, winY + 286, hoverClose ? 0xFF444444 : 0xFF222222);
            context.fill(winX + 140, winY + 266, winX + 220, winY + 267, 0x3CFFFFFF);
            context.fill(winX + 140, winY + 285, winX + 220, winY + 286, 0x3CFFFFFF);
            context.fill(winX + 140, winY + 266, winX + 141, winY + 286, 0x3CFFFFFF);
            context.fill(winX + 219, winY + 266, winX + 220, winY + 286, 0x3CFFFFFF);
            context.text(minecraft.font, "Close", winX + 140 + (80 - minecraft.font.width("Close")) / 2, winY + 266 + 6, 0xFFFFFFFF, true);

            boolean mouseDown = GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (mouseDown && !wasMouseDown) {
                if (hoverOpen && selectedFile != null) { 
                    // REPLACES DEFAULT VARIABLE WITH THE NEW DYNAMIC NAME
                    if (mode.equals("background")) selectedBgName = selectedFile.getName();
                    else selectedIntroName = selectedFile.getName();
                    this.onClose();
                }
                else if (hoverClose) this.onClose();
                else if (mouseX >= winX + 10 && mouseX <= winX + 230 && mouseY >= winY + 26 && mouseY <= winY + 256) {
                    int clickedIdx = (mouseY - startY) / itemH;
                    if (clickedIdx >= 0 && clickedIdx < availableVideos.size()) {
                        selectedFile = availableVideos.get(clickedIdx);
                    }
                }
            }
            wasMouseDown = mouseDown;
            super.extractRenderState(context, mouseX, mouseY, delta);
        }

        @Override public boolean isPauseScreen() { return false; }
    }
}