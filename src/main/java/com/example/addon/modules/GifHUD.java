package com.example.addon.modules;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.example.addon.screens.CachedSkiaTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public class GifHUD extends AddonModule {
    // KHAI BÁO TRƯỚC INSTANCE: static field khởi tạo theo thứ tự văn bản. Nếu để sau
    // INSTANCE, constructor (chạy lúc khởi tạo INSTANCE) gọi loadHistory() khi HISTORY_FILE
    // còn null → NPE bị nuốt → lịch sử không nạp được lúc dựng object.
    private static final File HISTORY_FILE = new File(FabricLoader.getInstance().getGameDir().toFile(), "kingthon_gifs.txt");

    public static final GifHUD INSTANCE = new GifHUD();
    public boolean active = false;

    private double posX = com.example.addon.util.HudPositions.getX("GifHUD", 50.0);
    private double posY = com.example.addon.util.HudPositions.getY("GifHUD", 50.0);
    public final SliderOption width      = new SliderOption(this, "Width",            "", 150.0, 10.0, 1000.0, 1.0);
    public final SliderOption height     = new SliderOption(this, "Height",           "", 150.0, 10.0, 1000.0, 1.0);
    public final SliderOption frameDelay = new SliderOption(this, "FrameDelay", "", 50.0,  10.0,  300.0, 1.0);
    public final ToggleOption loadClipboard = new ToggleOption(this, "LoadClipboard", "", false);
    public final ToggleOption prevGif       = new ToggleOption(this, "PrevGIF",      "", false);
    public final ToggleOption nextGif       = new ToggleOption(this, "NextGIF",      "", false);
    
    public final ToggleOption dropShadow    = new ToggleOption(this, "DropShadow", "Do bong Skia 3D.", true);
    public final ToggleOption parallax      = new ToggleOption(this, "Parallax", "Hieu ung luot (Sway) theo goc nhin va chuot.", true);
    public final SliderOption parallaxSpeed = new SliderOption(this, "ParallaxSpeed", "Do nhay cua Parallax.", 5.0, 1.0, 20.0, 0.5);

    private boolean isDraggingHUD = false;
    private double dragOffsetX = 0, dragOffsetY = 0;
    private boolean wasMouseDownEditor = false;

    private final List<Identifier>               gifFrames     = new ArrayList<>();
    private final List<DynamicTexture> frameTextures = new ArrayList<>();
    private int     currentFrame  = 0;
    private long    lastFrameTime = 0;
    private boolean isLoading     = false;
    private String  currentUrl    = "";

    private float parallaxX = 0f;
    private float parallaxY = 0f;
    private float prevYaw = 0f;
    private float prevPitch = 0f;

    private CachedSkiaTexture shadowTex;

    private final List<String> gifHistory = new ArrayList<>();
    private int historyIndex = -1;
    // Auto-load GIF gần nhất bị hoãn tới onTick (khi đã vào thế giới, render sẵn sàng).
    private boolean pendingAutoLoad = false;

    private GifHUD() {
        super("GifHUD", "Display GIF on HUD with Skia Shadow & Parallax.");
        loadHistory();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("example-addon", "gifhud"), (context, tracker) -> {
            if (this.active) render(context);
        });
    }

    @Override
    public void onEnable() {
        this.active = true;
        // KHÔNG load GIF ngay tại đây: onEnable có thể chạy lúc nạp config (rất sớm, render
        // chưa sẵn sàng) → texture đăng ký hỏng nên GIF gần nhất không hiện khi khởi động lại.
        // Thay vào đó đánh dấu hoãn, để onTick load khi đã vào thế giới (giống nút Prev/Next).
        if (gifHistory.isEmpty()) loadHistory();
        if (gifFrames.isEmpty() && !gifHistory.isEmpty()) pendingAutoLoad = true;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameRenderer.getMainCamera() != null) {
            prevYaw = mc.gameRenderer.getMainCamera().yRot(); 
            prevPitch = mc.gameRenderer.getMainCamera().xRot(); 
            parallaxX = 0; parallaxY = 0;
        }
    }

    @Override public void onDisable() {
        this.active = false;
        if (shadowTex != null) { shadowTex.dispose(); shadowTex = null; }
    }

    private void drawOutline(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        context.fill(x - 1, y - 1, x + w + 1, y, color);
        context.fill(x - 1, y + h, x + w + 1, y + h + 1, color);
        context.fill(x - 1, y, x, y + h, color);
        context.fill(x + w, y, x + w + 1, y + h, color);
    }

    /**
     * Renders the drop shadow offscreen (CPU raster, no live GL framebuffer access)
     * and composites it via the normal GuiGraphicsExtractor.blit path. The raw-GL FBO
     * hack this used to use gets silently overwritten by the GUI submission phase in
     * 26.1.2's extraction/submission render pipeline — this is immune to that.
     */
    private void drawSkiaShadow(GuiGraphicsExtractor context, double x, double y, double w, double h, float blurRadius, int colorRgb) {
        Minecraft mc = Minecraft.getInstance();
        float scale = (float) mc.getWindow().getGuiScale();
        float margin = blurRadius * 3f;
        int pw = Math.round((float)(w + margin * 2) * scale);
        int ph = Math.round((float)(h + margin * 2) * scale);
        if (pw <= 0 || ph <= 0) return;

        if (shadowTex == null) shadowTex = new CachedSkiaTexture("gifhud_shadow");
        // Shadow shape depends only on size/blur/colour/scale (NOT the GIF frames),
        // so this rasters once and is reused every frame thereafter.
        String key = pw + "x" + ph + "|" + blurRadius + "|" + colorRgb;
        shadowTex.render(pw, ph, key, canvas -> {
            canvas.scale(scale, scale);
            try (Paint paint = new Paint();
                 ImageFilter shadowFilter = ImageFilter.makeDropShadowOnly(0, 0, blurRadius, blurRadius, colorRgb)) {
                paint.setImageFilter(shadowFilter);
                paint.setAntiAlias(true);
                canvas.drawRect(Rect.makeXYWH(margin, margin, (float)w, (float)h), paint);
            }
        });
        shadowTex.blit(context, (int)Math.round(x - margin), (int)Math.round(y - margin),
            (int)Math.round(w + margin * 2), (int)Math.round(h + margin * 2));
    }

    private void render(GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui && !(mc.screen instanceof MusicHUD.FontScreen)) return;

        if (isLoading) {
            context.text(mc.font, "Loading GIF...", (int)posX, (int)posY, 0xFFFFFF00, true);
            return;
        }

        long now = System.currentTimeMillis();
        long delay = (long)(double) frameDelay.getValue();
        if (!gifFrames.isEmpty() && now - lastFrameTime >= delay) {
            currentFrame = (currentFrame + 1) % gifFrames.size();
            lastFrameTime = now;
        }

        Identifier frame = gifFrames.isEmpty() ? null : gifFrames.get(currentFrame);

        if (parallax.getValue() && mc.player != null && mc.gameRenderer.getMainCamera() != null) {
            float intensity = (float)(double) parallaxSpeed.getValue();
            float targetX = 0, targetY = 0;

            if (mc.screen != null) {
                // ĐÃ FIX: Tắt Parallax chuột khi mở Chat / Inventory
                targetX = 0;
                targetY = 0;
                prevYaw = mc.gameRenderer.getMainCamera().yRot(); 
                prevPitch = mc.gameRenderer.getMainCamera().xRot();
            } else {
                // Chỉ áp dụng Parallax khi xoay Camera trong lúc chơi
                float yaw = mc.gameRenderer.getMainCamera().yRot();
                float pitch = mc.gameRenderer.getMainCamera().xRot();
                
                float dYaw = net.minecraft.util.Mth.wrapDegrees(yaw - prevYaw);
                float dPitch = pitch - prevPitch;
                dYaw = net.minecraft.util.Mth.clamp(dYaw, -20f, 20f);
                dPitch = net.minecraft.util.Mth.clamp(dPitch, -20f, 20f);
                
                targetX = -dYaw * intensity * 0.8f; 
                targetY = -dPitch * intensity * 0.8f;
                
                prevYaw = yaw; 
                prevPitch = pitch;
            }

            parallaxX += (targetX - parallaxX) * 0.15f; 
            parallaxY += (targetY - parallaxY) * 0.15f;
            if (mc.screen == null) { parallaxX *= 0.8f; parallaxY *= 0.8f; }
        else {
                // Tăng tốc độ hồi vị trí gốc êm ái khi đang bật GUI
                parallaxX *= 0.5f; 
                parallaxY *= 0.5f; 
            }
        } else {
            parallaxX = 0; parallaxY = 0;
            if (mc.player != null && mc.gameRenderer.getMainCamera() != null) { 
                prevYaw = mc.gameRenderer.getMainCamera().yRot(); 
                prevPitch = mc.gameRenderer.getMainCamera().xRot(); 
            }
        }

        double baseX = posX;
        double baseY = posY;
        double w = width.getValue();
        double h = height.getValue();

        double realX = baseX + parallaxX;
        double realY = baseY + parallaxY;

        if (dropShadow.getValue() && frame != null) {
            int shadowColor = new java.awt.Color(0, 0, 0, 160).getRGB();
            drawSkiaShadow(context, realX, realY, w, h, 18f, shadowColor);
        }

        context.pose().pushMatrix();
        context.pose().translate((float)realX, (float)realY);

        if (HUDEditor.INSTANCE.active) {
            double scale = mc.getWindow().getGuiScale();
            double mx = mc.mouseHandler.xpos() / scale;
            double my = mc.mouseHandler.ypos() / scale;
            boolean mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

            if (mouseDown && !wasMouseDownEditor) {
                if (mx >= realX && mx <= realX + w && my >= realY && my <= realY + h) {
                    if (HUDEditor.draggingHUD.isEmpty() || HUDEditor.draggingHUD.equals("GifHUD")) {
                        isDraggingHUD = true; HUDEditor.draggingHUD = "GifHUD";
                        dragOffsetX = mx - realX; dragOffsetY = my - realY;
                    }
                }
            } else if (!mouseDown) {
                if (isDraggingHUD) { HUDEditor.draggingHUD = ""; com.example.addon.util.HudPositions.save("GifHUD", posX, posY); }
                isDraggingHUD = false;
            }

            if (isDraggingHUD && mouseDown) {
                baseX = mx - dragOffsetX - parallaxX; 
                baseY = my - dragOffsetY - parallaxY;
                int screenW = mc.getWindow().getGuiScaledWidth();
                int screenH = mc.getWindow().getGuiScaledHeight();
                baseX = Math.max(0, Math.min(baseX, screenW - w));
                baseY = Math.max(0, Math.min(baseY, screenH - h));
                posX = baseX; posY = baseY;
                drawOutline(context, 0, 0, (int)w, (int)h, 0xFF00FF00); 
            } else if (mx >= realX && mx <= realX + w && my >= realY && my <= realY + h) {
                drawOutline(context, 0, 0, (int)w, (int)h, 0xFFFFFF00); 
            }
            wasMouseDownEditor = mouseDown;
        }

        if (frame == null) {
            context.text(mc.font, "No GIF Loaded", 0, 0, 0xFFFF5555, true);
        } else {
            context.blit(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0, 0, (int)w, (int)h, (int)w, (int)h);
        }

        context.pose().popMatrix();
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Auto-load GIF gần nhất sau khi vào thế giới (đã hoãn từ onEnable cho an toàn).
        if (pendingAutoLoad && !isLoading && gifFrames.isEmpty()) {
            pendingAutoLoad = false;
            if (gifHistory.isEmpty()) loadHistory();
            if (!gifHistory.isEmpty()) {
                int idx = historyIndex >= 0 ? historyIndex : gifHistory.size() - 1;
                currentUrl = gifHistory.get(idx);
                loadGifAsync(currentUrl);
            }
        }

        if (loadClipboard.getValue()) {
            loadClipboard.setValue(false);
            String clipboard = mc.keyboardHandler.getClipboard();
            if (clipboard != null && clipboard.startsWith("http")) {
                if (clipboard.equals(currentUrl)) {
                    mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§d[GifHUD] §eLink is already loaded!"));
                } else {
                    currentUrl = clipboard;
                    loadGifAsync(clipboard);
                }
            } else {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§d[GifHUD] §cPlease copy a valid GIF URL to the clipboard!"));
            }
        }

        if (prevGif.getValue()) {
            prevGif.setValue(false);
            if (!gifHistory.isEmpty()) {
                historyIndex = (historyIndex - 1 + gifHistory.size()) % gifHistory.size();
                currentUrl = gifHistory.get(historyIndex);
                loadGifAsync(currentUrl);
            } else {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§d[GifHUD] §cNone of GIFS saved!"));
            }
        }

        if (nextGif.getValue()) {
            nextGif.setValue(false);
            if (!gifHistory.isEmpty()) {
                historyIndex = (historyIndex + 1) % gifHistory.size();
                currentUrl = gifHistory.get(historyIndex);
                loadGifAsync(currentUrl);
            } else {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§d[GifHUD] §cNone of GIFS saved!"));
            }
        }
    }

    private void loadHistory() {
        gifHistory.clear();
        try {
            if (HISTORY_FILE.exists()) {
                List<String> lines = Files.readAllLines(HISTORY_FILE.toPath());
                for (String line : lines) {
                    String clean = line.trim();
                    if (!clean.isEmpty()) gifHistory.add(clean);
                }
                if (!gifHistory.isEmpty()) historyIndex = gifHistory.size() - 1;
            }
        } catch (Exception ignored) {}
    }

    private void saveToHistory(String url) {
        if (!gifHistory.contains(url)) {
            gifHistory.add(url);
            historyIndex = gifHistory.size() - 1;
        } else {
            historyIndex = gifHistory.indexOf(url);
        }
        try {
            Files.writeString(HISTORY_FILE.toPath(), String.join("\n", gifHistory));
        } catch (Exception ignored) {}
    }

    private String resolveDirectGifLink(String link) throws Exception {
        if (link.contains("tenor.com/view/")) {
            URL url = new URL(link);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("Tenor page returned HTTP " + code);
            String html = new String(conn.getInputStream().readAllBytes(), "UTF-8");
            conn.disconnect();

            // Tenor shards its CDN across numbered subdomains (media.tenor.com,
            // media1.tenor.com, media2.tenor.com, ...) in addition to c.tenor.com —
            // must match all of them, not just the bare "media." host.
            final String TENOR_CDN = "https://(?:media[0-9]*|c)\\.tenor\\.com/[^\"]+\\.gif";
            // og:image / og:video content attribute — checked FIRST: this meta tag is
            // curated by Tenor to represent THIS specific page's gif, unlike the JSON
            // scrape below which can match an unrelated "related gifs" data block and
            // silently return the wrong gif.
            Matcher m = Pattern.compile(
                "property=\"og:(?:image|video)\" content=\"(" + TENOR_CDN + ")\"").matcher(html);
            if (m.find()) return m.group(1);
            // Fallback: content attribute regardless of preceding property order
            m = Pattern.compile("content=\"(" + TENOR_CDN + ")\"").matcher(html);
            if (m.find()) return m.group(1);
            // Modern Tenor embeds all media in __NEXT_DATA__ JSON (Next.js).
            m = Pattern.compile("\"url\":\"(" + TENOR_CDN + ")\"").matcher(html);
            if (m.find()) return m.group(1);
            // Any quoted tenor GIF URL anywhere on the page
            m = Pattern.compile("\"(" + TENOR_CDN + ")\"").matcher(html);
            if (m.find()) return m.group(1);

            throw new Exception(
                "Tenor: GIF nay khong lay duoc URL truc tiep. Dan link .gif thang vao.");
        } else if (link.contains("giphy.com/gifs/")) {
            // Slug format: some-description-<base62id>[?query]
            String path = link.replaceAll("\\?.*", "").replaceAll("/$", "");
            String[] parts = path.split("-");
            String id = parts[parts.length - 1].replaceAll("[^a-zA-Z0-9]", "");
            if (id.isEmpty())
                throw new Exception("Giphy: khong parse duoc GIF ID tu URL nay.");
            return "https://media.giphy.com/media/" + id + "/giphy.gif";
        }
        return link;
    }

    private void loadGifAsync(String rawUrl) {
        if (isLoading) return;
        isLoading = true;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.execute(() -> mc.player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§d[GifHUD] §aLoading GIF...")));

        CompletableFuture.runAsync(() -> {
            try {
                String directUrl = resolveDirectGifLink(rawUrl);
                URL url = new URL(directUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(10000);
                int httpCode = conn.getResponseCode();
                if (httpCode != 200) {
                    String hint = (httpCode == 404 && rawUrl.contains("cdn.discordapp.com"))
                        ? "HTTP 404 - Link Discord CDN da het han. Dan link .gif moi vao."
                        : "HTTP " + httpCode;
                    throw new Exception(hint);
                }

                InputStream in = conn.getInputStream();
                byte[] gifBytes = in.readAllBytes();
                in.close(); conn.disconnect();

                ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
                ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes));
                reader.setInput(iis, false);
                int numFrames = reader.getNumImages(true);

                int gifW = reader.getWidth(0), gifH = reader.getHeight(0);
                BufferedImage canvas = new BufferedImage(gifW, gifH, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2 = canvas.createGraphics();

                List<byte[]> rawFrames = new ArrayList<>();
                for (int i = 0; i < numFrames; i++) {
                    g2.drawImage(reader.read(i), 0, 0, null);
                    BufferedImage baked = new BufferedImage(gifW, gifH, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D gc = baked.createGraphics();
                    gc.drawImage(canvas, 0, 0, null);
                    gc.dispose();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(baked, "png", baos);
                    rawFrames.add(baos.toByteArray());
                }
                g2.dispose(); reader.dispose(); iis.close();

                mc.execute(() -> {
                    clearFrames();
                    try {
                        for (int i = 0; i < rawFrames.size(); i++) {
                            final int fi = i;
                            NativeImage img = NativeImage.read(new ByteArrayInputStream(rawFrames.get(fi)));
                            Identifier fid = Identifier.fromNamespaceAndPath("gifhud", "frame_" + System.nanoTime() + "_" + fi);
                            DynamicTexture tex = new DynamicTexture(() -> "gifhud_" + fi, img);
                            mc.getTextureManager().register(fid, tex);
                            gifFrames.add(fid);
                            frameTextures.add(tex);
                        }
                        saveToHistory(rawUrl);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        isLoading = false;
                        currentFrame = 0;
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                mc.execute(() -> {
                    if (mc.player != null)
                        mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "§d[GifHUD] §cError loading link! " + e.getMessage()));
                    isLoading = false;
                });
            }
        });
    }

    private void clearFrames() {
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < gifFrames.size(); i++) {
            if (gifFrames.get(i) != null) mc.getTextureManager().release(gifFrames.get(i));
            if (frameTextures.get(i) != null) frameTextures.get(i).close();
        }
        gifFrames.clear();
        frameTextures.clear();
    }
}