package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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

public class GifHUD extends AddonModule {
    public static final GifHUD INSTANCE = new GifHUD();
    public boolean active = false;

    // ── OPTIONS ──
    public final SliderOption posX      = new SliderOption(this, "X Position",       "", 50.0,  0.0, 2000.0, 1.0);
    public final SliderOption posY      = new SliderOption(this, "Y Position",       "", 50.0,  0.0, 1000.0, 1.0);
    public final SliderOption width     = new SliderOption(this, "Width",            "", 150.0, 10.0, 1000.0, 1.0);
    public final SliderOption height    = new SliderOption(this, "Height",           "", 150.0, 10.0, 1000.0, 1.0);
    public final SliderOption frameDelay = new SliderOption(this, "Frame Delay (ms)","", 50.0,  10.0,  300.0, 1.0);
    public final ToggleOption loadClipboard = new ToggleOption(this, "Load from Clipboard", "", false);
    public final ToggleOption prevGif       = new ToggleOption(this, "Prev Saved GIF",      "", false);
    public final ToggleOption nextGif       = new ToggleOption(this, "Next Saved GIF",      "", false);

    // ── GIF STATE ──
    private final List<Identifier>               gifFrames     = new ArrayList<>();
    private final List<NativeImageBackedTexture> frameTextures = new ArrayList<>();
    private int     currentFrame  = 0;
    private long    lastFrameTime = 0;
    private boolean isLoading     = false;
    private String  currentUrl    = "";

    // ── HISTORY ──
    private static final File HISTORY_FILE = new File(
        FabricLoader.getInstance().getGameDir().toFile(), "kingthon_gifs.txt");
    private final List<String> gifHistory = new ArrayList<>();
    private int historyIndex = -1;

    private GifHUD() {
        super("GifHUD", "Display GIF on HUD");
        loadHistory();
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            if (this.active) render(context);
        });
    }

    @Override
    public void onEnable() {
        this.active = true;
        // Khi enable: nếu chưa có frame nào thì tải lại từ lịch sử
        if (gifFrames.isEmpty()) {
            // Đọc lại history để chắc chắn (phòng trường hợp file được tạo sau constructor)
            if (gifHistory.isEmpty()) loadHistory();
            if (!gifHistory.isEmpty()) {
                int idx = historyIndex >= 0 ? historyIndex : gifHistory.size() - 1;
                currentUrl = gifHistory.get(idx);
                loadGifAsync(currentUrl);
            }
        }
    }

    @Override
    public void onDisable() {
        this.active = false;
        // KHÔNG clear frames — giữ nguyên để enable lại không cần tải lại
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
                    mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifHUD] §eLink này đang hiển thị rồi!"), false);
                } else {
                    currentUrl = clipboard;
                    loadGifAsync(clipboard);
                }
            } else {
                mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifHUD] §cClipboard không có link http!"), false);
            }
        }

        if (prevGif.getValue()) {
            prevGif.setValue(false);
            if (!gifHistory.isEmpty()) {
                historyIndex = (historyIndex - 1 + gifHistory.size()) % gifHistory.size();
                currentUrl = gifHistory.get(historyIndex);
                loadGifAsync(currentUrl);
            } else {
                mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifHUD] §cChưa có GIF nào được lưu!"), false);
            }
        }

        if (nextGif.getValue()) {
            nextGif.setValue(false);
            if (!gifHistory.isEmpty()) {
                historyIndex = (historyIndex + 1) % gifHistory.size();
                currentUrl = gifHistory.get(historyIndex);
                loadGifAsync(currentUrl);
            } else {
                mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifHUD] §cChưa có GIF nào được lưu!"), false);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RENDER
    // ─────────────────────────────────────────────────────────────

    private void render(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;

        if (isLoading) {
            context.drawText(mc.textRenderer, "Loading GIF...",
                (int)(double)posX.getValue(), (int)(double)posY.getValue(), 0xFFFFFF00, true);
            return;
        }

        if (gifFrames.isEmpty()) {
            context.drawText(mc.textRenderer, "No GIF (copy link → Load from Clipboard)",
                (int)(double)posX.getValue(), (int)(double)posY.getValue(), 0xFFFF5555, true);
            return;
        }

        long now   = System.currentTimeMillis();
        long delay = (long)(double) frameDelay.getValue();
        if (now - lastFrameTime >= delay) {
            currentFrame = (currentFrame + 1) % gifFrames.size();
            lastFrameTime = now;
        }

        Identifier frame = gifFrames.get(currentFrame);
        if (frame == null) return;

        int x = (int)(double) posX.getValue();
        int y = (int)(double) posY.getValue();
        int w = (int)(double) width.getValue();
        int h = (int)(double) height.getValue();
        context.drawTexture(RenderPipelines.GUI_TEXTURED, frame, x, y, 0, 0, w, h, w, h);
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
                net.minecraft.text.Text.literal("§d[GifHUD] §aĐang tải GIF..."), false));

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
                            Identifier fid = Identifier.of("gifhud", "frame_" + System.nanoTime() + "_" + fi);
                            NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "gifhud_" + fi, img);
                            mc.getTextureManager().registerTexture(fid, tex);
                            gifFrames.add(fid);
                            frameTextures.add(tex);
                        }
                        saveToHistory(rawUrl);
                        if (mc.player != null)
                            mc.player.sendMessage(net.minecraft.text.Text.literal(
                                "§d[GifHUD] §eTải xong! (" + gifFrames.size() + " frames)"), false);
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
                            "§d[GifHUD] §cLỗi tải link! " + e.getMessage()), false);
                    isLoading = false;
                });
            }
        });
    }

    private void clearFrames() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (int i = 0; i < gifFrames.size(); i++) {
            if (gifFrames.get(i) != null) mc.getTextureManager().destroyTexture(gifFrames.get(i));
            if (frameTextures.get(i) != null) frameTextures.get(i).close();
        }
        gifFrames.clear();
        frameTextures.clear();
    }
}