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

    public final SliderOption frameDelay = new SliderOption(this, "Frame Delay (ms)", "Delay between GIF frames.", 50.0, 10.0, 300.0, 1.0);
    public final SliderOption dimOverlay = new SliderOption(this, "Dim Overlay", "Darkness overlay to make text readable.", 100.0, 0.0, 255.0, 1.0);
    public final ToggleOption loadClipboard = new ToggleOption(this, "Load from Clipboard", "Load GIF link from clipboard.", false);
    public final ToggleOption prevGif = new ToggleOption(this, "Prev Saved GIF", "Load previous GIF.", false);
    public final ToggleOption nextGif = new ToggleOption(this, "Next Saved GIF", "Load next GIF.", false);

    private final List<Identifier> gifFrames = new ArrayList<>();
    private final List<NativeImageBackedTexture> frameTextures = new ArrayList<>();
    
    // --- BIẾN LƯU TỌA ĐỘ SCISSOR ---
    private final List<int[]> currentFrameBounds = new ArrayList<>();
    private final List<int[]> lastFrameBounds = new ArrayList<>();

    private int currentFrame = 0;
    private long lastFrameTime = 0;
    private boolean isLoading = false;
    private String currentUrl = "";

    private static final File HISTORY_FILE = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze_gui_gifs.txt");
    private final List<String> gifHistory = new ArrayList<>();
    private int historyIndex = -1;

    public GifGUI() {
        super("GifGUI", "Masks the GIF specifically into the 7 category panels using Scissor-stealing.");
        loadHistory();

        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.beforeRender(screen).register((screenObj, context, mouseX, mouseY, tickDelta) -> {
                if (this.active) renderBackground(context, scaledWidth, scaledHeight);
            });
        });
    }

    @Override
    public void onEnable() {
        this.active = true;
        if (gifFrames.isEmpty() && !gifHistory.isEmpty()) {
            currentUrl = gifHistory.get(historyIndex);
            loadGifAsync(currentUrl);
        }
    }

    @Override
    public void onDisable() {
        this.active = false;
        clearFrames();
    }

    // --- HÀM NÀY ĐƯỢC GỌI TỪ MIXIN MỖI KHI BOZE BẬT SCISSOR ---
    public void addScissorBound(int x1, int y1, int x2, int y2) {
        int w = x2 - x1;
        int h = y2 - y1;
        // Chỉ bắt những khung bự (Lọc bỏ các tooltip hoặc button lắt nhắt)
        if (w > 80 && h > 50) {
            // Kiểm tra trùng lặp để không vẽ đè nhiều lần
            for (int[] b : currentFrameBounds) {
                if (b[0] == x1 && b[1] == y1) return;
            }
            currentFrameBounds.add(new int[]{x1, y1, w, h});
        }
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (loadClipboard.getValue()) {
            loadClipboard.setValue(false);
            String clipboard = mc.keyboard.getClipboard();
            if (clipboard != null && clipboard.startsWith("http")) {
                currentUrl = clipboard;
                loadGifAsync(clipboard);
            } else {
                if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §cNo valid link!"), false);
            }
        }
        if (prevGif.getValue() && !gifHistory.isEmpty()) {
            prevGif.setValue(false);
            historyIndex = (historyIndex - 1 < 0) ? gifHistory.size() - 1 : historyIndex - 1;
            loadGifAsync(gifHistory.get(historyIndex));
        }
        if (nextGif.getValue() && !gifHistory.isEmpty()) {
            nextGif.setValue(false);
            historyIndex = (historyIndex + 1 >= gifHistory.size()) ? 0 : historyIndex + 1;
            loadGifAsync(gifHistory.get(historyIndex));
        }
    }

    private void renderBackground(DrawContext context, int width, int height) {
        if (isLoading || gifFrames.isEmpty()) {
            // Nhớ clear để không bị rác tọa độ khi tắt GUI
            currentFrameBounds.clear();
            return; 
        }

        long now = System.currentTimeMillis();
        long delay = (long)(double) frameDelay.getValue();

        if (now - lastFrameTime >= delay) {
            currentFrame++;
            if (currentFrame >= gifFrames.size()) currentFrame = 0;
            lastFrameTime = now;
        }

        Identifier activeFrame = gifFrames.get(currentFrame);
        if (activeFrame != null && !lastFrameBounds.isEmpty()) {
            // KHOÉT 7 CÁI LỖ TRÊN MÀN HÌNH VÀ VẼ GIF VÀO ĐÓ
            for (int[] bound : lastFrameBounds) {
                int bx = bound[0];
                // Kéo ngược tọa độ Y lên 25 pixel để lấp luôn cái Header (Chữ COMBAT, MOVEMENT...)
                int by = bound[1] - 25; 
                int bw = bound[2];
                // Cộng thêm độ cao để lấp lại phần bị kéo lên
                int bh = bound[3] + 25; 

                // Chống tràn màn hình cạnh trên
                if (by < 0) {
                    bh += by;
                    by = 0;
                }

                // Vẽ GIF crop theo đúng tọa độ X, Y của panel
                context.drawTexture(RenderPipelines.GUI_TEXTURED, activeFrame, bx, by, (float)bx, (float)by, bw, bh, width, height);
                
                // Đổ mờ (Dim) để thấy rõ chữ
                int dimAlpha = (int)(double) dimOverlay.getValue();
                if (dimAlpha > 0) {
                    int overlayColor = new Color(0, 0, 0, dimAlpha).getRGB();
                    context.fill(bx, by, bx + bw, by + bh, overlayColor);
                }
            }
        }

        // Chốt sổ tọa độ frame hiện tại, ném qua lastFrameBounds để dùng cho Frame sau
        lastFrameBounds.clear();
        lastFrameBounds.addAll(currentFrameBounds);
        currentFrameBounds.clear();
    }

    private void loadHistory() {
        try {
            if (HISTORY_FILE.exists()) {
                gifHistory.addAll(Files.readAllLines(HISTORY_FILE.toPath()).stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
                if (!gifHistory.isEmpty()) historyIndex = gifHistory.size() - 1;
            }
        } catch (Exception ignored) {}
    }

    private void saveToHistory(String url) {
        if (!gifHistory.contains(url)) {
            gifHistory.add(url);
            historyIndex = gifHistory.size() - 1;
            try { Files.writeString(HISTORY_FILE.toPath(), String.join("\n", gifHistory)); } catch (Exception ignored) {}
        } else {
            historyIndex = gifHistory.indexOf(url);
        }
    }

    private String resolveDirectGifLink(String link) {
        try {
            if (link.contains("tenor.com/view/")) {
                HttpURLConnection conn = (HttpURLConnection) new URL(link).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                Matcher m = Pattern.compile("property=\"og:image\"\\s+content=\"(https://[^\"]+\\.gif)\"").matcher(new String(conn.getInputStream().readAllBytes(), "UTF-8"));
                if (m.find()) return m.group(1); 
            } else if (link.contains("giphy.com/gifs/")) {
                return "https://media.giphy.com/media/" + link.split("-")[link.split("-").length - 1] + "/giphy.gif";
            }
        } catch (Exception ignored) {}
        return link; 
    }

    private void loadGifAsync(String rawUrl) {
        if (isLoading) return;
        isLoading = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §aProcessing GIF..."), false);

        CompletableFuture.runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(resolveDirectGifLink(rawUrl)).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                byte[] bytes = conn.getInputStream().readAllBytes();
                
                ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
                ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
                reader.setInput(iis, false);
                
                int numFrames = reader.getNumImages(true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                List<byte[]> rawFrames = new ArrayList<>();
                
                BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2d = canvas.createGraphics();
                
                for (int i = 0; i < numFrames; i++) {
                    g2d.drawImage(reader.read(i), 0, 0, null);
                    BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    frame.createGraphics().drawImage(canvas, 0, 0, null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(frame, "png", baos);
                    rawFrames.add(baos.toByteArray());
                }
                
                mc.execute(() -> {
                    clearFrames();
                    try {
                        for (int i = 0; i < rawFrames.size(); i++) {
                            NativeImage img = NativeImage.read(new ByteArrayInputStream(rawFrames.get(i)));
                            Identifier id = Identifier.of("gifgui", "bg_" + System.currentTimeMillis() + "_" + i);
                            NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "gif_frame", img);
                            mc.getTextureManager().registerTexture(id, tex);
                            gifFrames.add(id);
                            frameTextures.add(tex);
                        }
                        saveToHistory(rawUrl);
                        if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §eLoaded successfully!"), false);
                    } catch (Exception e) { e.printStackTrace(); }
                    finally { isLoading = false; currentFrame = 0; }
                });
            } catch (Exception e) { 
                isLoading = false; 
                if (mc.player != null) mc.execute(() -> mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §cFailed to load GIF!"), false));
            }
        });
    }

    private void clearFrames() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (int i = 0; i < gifFrames.size(); i++) {
            if (gifFrames.get(i) != null) mc.getTextureManager().destroyTexture(gifFrames.get(i));
            if (frameTextures.get(i) != null) frameTextures.get(i).close();
        }
        gifFrames.clear(); frameTextures.clear();
    }
}