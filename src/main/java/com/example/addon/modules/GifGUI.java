package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
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

public class GifGUI extends AddonModule {
    public static final GifGUI INSTANCE = new GifGUI();
    public boolean active = false;

    // ── OPTIONS ──
    public final SliderOption frameDelay = new SliderOption(this, "Frame Delay (ms)", "Tốc độ phát GIF (ms).", 50.0, 10.0, 300.0, 1.0);
    public final SliderOption dimOverlay = new SliderOption(this, "Dim Overlay",      "Độ tối lớp mờ phủ lên GIF.", 80.0, 0.0, 255.0, 1.0);
    public final ToggleOption loadClipboard = new ToggleOption(this, "Load from Clipboard", "Lấy link GIF từ Clipboard.", false);
    public final ToggleOption prevGif       = new ToggleOption(this, "Prev Saved GIF",      "Quay lại GIF trước.",        false);
    public final ToggleOption nextGif       = new ToggleOption(this, "Next Saved GIF",      "Chuyển sang GIF tiếp theo.", false);

    // ── GIF STATE ──
    private final List<Identifier>               gifFrames    = new ArrayList<>();
    private final List<NativeImageBackedTexture> frameTextures = new ArrayList<>();
    private int     currentFrame  = 0;
    private long    lastFrameTime = 0;
    private boolean isLoading     = false;
    private String  currentUrl    = "";

    // ── PUBLIC: DrawContextMixin đọc frame hiện tại ──
    public Identifier getCurrentFrameId() {
        if (gifFrames.isEmpty()) return null;
        // Advance frame dựa trên thời gian
        long now   = System.currentTimeMillis();
        long delay = (long)(double) frameDelay.getValue();
        if (now - lastFrameTime >= delay) {
            currentFrame  = (currentFrame + 1) % gifFrames.size();
            lastFrameTime = now;
        }
        return gifFrames.get(currentFrame);
    }

    // ── HISTORY ──
    private static final File HISTORY_FILE = new File(
        FabricLoader.getInstance().getGameDir().toFile(), "boze_gui_gifs.txt");
    private final List<String> gifHistory = new ArrayList<>();
    private int historyIndex = -1;

    private GifGUI() {
        super("GifGUI", "Hình nền GIF động cho Boze ClickGUI và Main Menu.");
        loadHistory();

        // TitleScreen: vẫn dùng ScreenEvents vì DrawContextMixin chỉ hook Boze ClickGUI
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.beforeRender(screen).register((screenObj, context, mouseX, mouseY, tickDelta) -> {
                if (!this.active) return;
                if (!(screenObj instanceof TitleScreen)) return;
                renderBackground(context, scaledWidth, scaledHeight);
            });
        });
    }

    @Override
    public void onEnable() {
        this.active = true;
        // Tải lại GIF từ lịch sử nếu chưa có frame nào (bao gồm sau khi restart game)
        if (gifFrames.isEmpty() && !gifHistory.isEmpty()) {
            // Đọc lại history phòng trường hợp constructor chạy trước khi file được tạo
            if (gifHistory.isEmpty()) loadHistory();
            if (!gifHistory.isEmpty()) {
                currentUrl = gifHistory.get(historyIndex >= 0 ? historyIndex : gifHistory.size() - 1);
                loadGifAsync(currentUrl);
            }
        }
    }

    @Override
    public void onDisable() {
        this.active = false;
        // KHÔNG clear frames khi disable để tránh phải tải lại khi enable lại
        // Chỉ clear khi load GIF mới
    }

    // ─────────────────────────────────────────────────────────────
    // HISTORY
    // ─────────────────────────────────────────────────────────────

    private void loadHistory() {
        gifHistory.clear();
        try {
            if (HISTORY_FILE.exists()) {
                List<String> lines = Files.readAllLines(HISTORY_FILE.toPath());
                for (String line : lines) {
                    String clean = line.trim();
                    if (!clean.isEmpty()) gifHistory.add(clean);
                }
                if (!gifHistory.isEmpty()) {
                    historyIndex = gifHistory.size() - 1;
                }
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

    // ─────────────────────────────────────────────────────────────
    // TICK
    // ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        if (loadClipboard.getValue()) {
            loadClipboard.setValue(false);
            String clipboard = mc.keyboard.getClipboard();
            if (clipboard != null && clipboard.startsWith("http")) {
                if (clipboard.equals(currentUrl)) {
                    mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §eLink này đang được dùng rồi!"), false);
                } else {
                    currentUrl = clipboard;
                    loadGifAsync(clipboard);
                }
            } else {
                mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §cClipboard không có link http!"), false);
            }
        }

        if (prevGif.getValue()) {
            prevGif.setValue(false);
            if (!gifHistory.isEmpty()) {
                historyIndex = (historyIndex - 1 + gifHistory.size()) % gifHistory.size();
                currentUrl = gifHistory.get(historyIndex);
                loadGifAsync(currentUrl);
            } else {
                mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §cChưa có GIF nào được lưu!"), false);
            }
        }

        if (nextGif.getValue()) {
            nextGif.setValue(false);
            if (!gifHistory.isEmpty()) {
                historyIndex = (historyIndex + 1) % gifHistory.size();
                currentUrl = gifHistory.get(historyIndex);
                loadGifAsync(currentUrl);
            } else {
                mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §cChưa có GIF nào được lưu!"), false);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RENDER
    // ─────────────────────────────────────────────────────────────

    private void renderBackground(DrawContext context, int screenW, int screenH) {
        if (isLoading) return;
        Identifier frame = getCurrentFrameId();
        if (frame == null) return;

        context.drawTexture(RenderPipelines.GUI_TEXTURED, frame,
            0, 0, 0, 0, screenW, screenH, screenW, screenH);

        int dimAlpha = (int)(double) dimOverlay.getValue();
        if (dimAlpha > 0) {
            context.fill(0, 0, screenW, screenH,
                new Color(0, 0, 0, dimAlpha).getRGB());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LOAD GIF
    // ─────────────────────────────────────────────────────────────

    private String resolveDirectGifLink(String link) {
        try {
            if (link.contains("tenor.com/view/")) {
                URL url = new URL(link);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(5000);
                InputStream in = conn.getInputStream();
                String html = new String(in.readAllBytes(), "UTF-8");
                in.close(); conn.disconnect();
                Matcher m = Pattern.compile("property=\"og:image\"\\s+content=\"(https://[^\"]+\\.gif)\"").matcher(html);
                if (m.find()) return m.group(1);
                // Tenor fallback: tìm mediaUrl
                Matcher m2 = Pattern.compile("\"url\":\"(https://media\\.tenor\\.com/[^\"]+\\.gif)\"").matcher(html);
                if (m2.find()) return m2.group(1);
            } else if (link.contains("giphy.com/gifs/")) {
                String[] parts = link.split("-");
                String id = parts[parts.length - 1].replaceAll("[^a-zA-Z0-9]", "");
                return "https://media.giphy.com/media/" + id + "/giphy.gif";
            }
        } catch (Exception ignored) {}
        return link;
    }

    private void loadGifAsync(String rawUrl) {
        if (isLoading) return;
        isLoading = true;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null)
            mc.execute(() -> mc.player.sendMessage(
                net.minecraft.text.Text.literal("§d[GifGUI] §aĐang tải GIF..."), false));

        CompletableFuture.runAsync(() -> {
            try {
                String directUrl = resolveDirectGifLink(rawUrl);
                URL url = new URL(directUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(10000);
                if (conn.getResponseCode() != 200)
                    throw new Exception("HTTP " + conn.getResponseCode());

                InputStream in = conn.getInputStream();
                byte[] gifBytes = in.readAllBytes();
                in.close(); conn.disconnect();

                // Decode GIF frames
                ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
                ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(gifBytes));
                reader.setInput(iis, false);
                int numFrames = reader.getNumImages(true);

                int gifW = reader.getWidth(0), gifH = reader.getHeight(0);
                BufferedImage canvas = new BufferedImage(gifW, gifH, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2 = canvas.createGraphics();

                List<byte[]> rawFrames = new ArrayList<>();
                for (int i = 0; i < numFrames; i++) {
                    BufferedImage frame = reader.read(i);
                    g2.drawImage(frame, 0, 0, null);

                    BufferedImage baked = new BufferedImage(gifW, gifH, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D gc = baked.createGraphics();
                    gc.drawImage(canvas, 0, 0, null);
                    gc.dispose();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(baked, "png", baos);
                    rawFrames.add(baos.toByteArray());
                }
                g2.dispose(); reader.dispose(); iis.close();

                // Upload lên GL thread
                mc.execute(() -> {
                    clearFrames();
                    try {
                        for (int i = 0; i < rawFrames.size(); i++) {
                            final int fi = i;
                            NativeImage img = NativeImage.read(new ByteArrayInputStream(rawFrames.get(fi)));
                            Identifier fid = Identifier.of("gifgui", "bg_" + System.nanoTime() + "_" + fi);
                            NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "gifgui_" + fi, img);
                            mc.getTextureManager().registerTexture(fid, tex);
                            gifFrames.add(fid);
                            frameTextures.add(tex);
                        }
                        // Lưu vào file sau khi upload thành công
                        saveToHistory(rawUrl);
                        if (mc.player != null)
                            mc.player.sendMessage(net.minecraft.text.Text.literal(
                                "§d[GifGUI] §eTải xong! (" + gifFrames.size() + " frames)"), false);
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
                        mc.player.sendMessage(net.minecraft.text.Text.literal(
                            "§d[GifGUI] §cLỗi tải link! " + e.getMessage()), false);
                    isLoading = false;
                });
            }
        });
    }

    private void clearFrames() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (int i = 0; i < gifFrames.size(); i++) {
            Identifier id = gifFrames.get(i);
            NativeImageBackedTexture tex = frameTextures.get(i);
            if (id != null) mc.getTextureManager().destroyTexture(id);
            if (tex != null) tex.close();
        }
        gifFrames.clear();
        frameTextures.clear();
    }
}