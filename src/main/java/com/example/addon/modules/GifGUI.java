package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GifGUI extends AddonModule {
    public static final GifGUI INSTANCE = new GifGUI();
    public volatile boolean active = false;

    // ── OPTIONS (giữ nguyên các tính năng cũ) ──
    public final SliderOption frameDelay = new SliderOption(this, "Frame Delay (ms)", "Tốc độ phát GIF (ms).", 50.0, 10.0, 300.0, 1.0);
    public final SliderOption dimOverlay = new SliderOption(this, "Dim Overlay",      "Độ tối lớp mờ phủ lên GIF.", 80.0, 0.0, 255.0, 1.0);
    public final ToggleOption loadClipboard = new ToggleOption(this, "Load from Clipboard", "Lấy link GIF từ Clipboard.", false);
    public final ToggleOption prevGif       = new ToggleOption(this, "Prev Saved GIF",      "Quay lại GIF trước.",        false);
    public final ToggleOption nextGif       = new ToggleOption(this, "Next Saved GIF",      "Chuyển sang GIF tiếp theo.", false);
    // Bật cái này nếu GIF không hiện: in tên class của mọi Screen ra chat để biết
    // class GUI của Boze tên gì → chỉnh bộ lọc cho đúng.
    public final ToggleOption debugScreens  = new ToggleOption(this, "Debug Screen Names",  "In tên class Screen ra chat (chẩn lỗi).", false);

    // ── GIF STATE ── (chỉ đụng tới trên render thread qua mc.execute + lúc render)
    private final List<Identifier>               gifFrames     = new ArrayList<>();
    private final List<NativeImageBackedTexture> frameTextures = new ArrayList<>();
    private int     currentFrame  = 0;
    private long    lastFrameTime = 0;
    private volatile boolean isLoading = false;
    private String  currentUrl    = "";
    // Kích thước GỐC của GIF — BẮT BUỘC để drawTexture map UV đúng rồi kéo giãn full màn hình.
    private int     gifWidth      = 0;
    private int     gifHeight     = 0;

    // ── HISTORY ── (lưu URL theo thứ tự dùng gần nhất; dòng cuối = GIF đang xem)
    private static final File HISTORY_FILE = new File(
        FabricLoader.getInstance().getGameDir().toFile(), "boze_gui_gifs.txt");
    private final List<String> gifHistory = new ArrayList<>();
    private int historyIndex = -1;

    // Dùng cho debug: chỉ in mỗi tên class 1 lần
    private final Set<String> seenScreens = new HashSet<>();

    private GifGUI() {
        super("GifGUI", "Hình nền GIF động cho Boze ClickGUI và Main Menu.");
        loadHistory();
    }

    @Override
    public void onEnable() {
        this.active = true;
        // Khôi phục GIF đang xem ở phiên trước: textures không thể lưu ra đĩa nên
        // phải tải lại từ URL. Dòng cuối history chính là GIF dùng gần nhất.
        if (gifFrames.isEmpty() && !gifHistory.isEmpty()) {
            historyIndex = gifHistory.size() - 1;
            currentUrl = gifHistory.get(historyIndex);
            loadGifAsync(currentUrl);
        }
    }

    @Override
    public void onDisable() {
        this.active = false;
    }

    public Identifier getCurrentFrameId() {
        if (gifFrames.isEmpty()) return null;
        long now   = System.currentTimeMillis();
        long delay = (long)(double) frameDelay.getValue();
        if (now - lastFrameTime >= delay) {
            currentFrame  = (currentFrame + 1) % gifFrames.size();
            lastFrameTime = now;
        }
        if (currentFrame >= gifFrames.size()) currentFrame = 0;
        return gifFrames.get(currentFrame);
    }

    // ─────────────────────────────────────────────────────────────
    // ĐIỂM VÀO TỪ MIXIN: ScreenMixin gọi cái này ở HEAD renderWithTooltip
    // ─────────────────────────────────────────────────────────────

    public void onScreenRender(Screen screen, DrawContext context) {
        String cn = screen.getClass().getName();
        if (debugScreens.getValue()) debugScreenName(cn);

        String lc = cn.toLowerCase();
        boolean isBozeGui = lc.contains("boze") && lc.contains("gui");
        boolean isTitle   = screen instanceof TitleScreen;
        if (isBozeGui || isTitle) {
            renderBackground(context, screen.width, screen.height);
        }
    }

    private void debugScreenName(String cn) {
        if (!seenScreens.add(cn)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] §7Screen: §f" + cn), false);
        else
            System.out.println("[GifGUI] Screen: " + cn);
    }

    // ─────────────────────────────────────────────────────────────
    // RENDER GIF (full màn hình, kéo giãn đúng tỉ lệ texture)
    // ─────────────────────────────────────────────────────────────

    public void renderBackground(DrawContext context, int screenW, int screenH) {
        Identifier frame = getCurrentFrameId();
        if (frame == null) return;
        int gw = gifWidth, gh = gifHeight;
        if (gw <= 0 || gh <= 0) return;

        // FIX QUAN TRỌNG: bản cũ truyền textureWidth/Height = screenW/screenH trong
        // khi texture thật là gw×gh → UV sai bét, gần như không thấy gì. Phải dùng
        // overload có regionWidth/regionHeight: vẽ ra (screenW×screenH) nhưng lấy
        // mẫu toàn bộ texture gốc (gw×gh) → kéo giãn lấp đầy màn hình.
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED, frame,
            0, 0,               // x, y
            0f, 0f,             // u, v
            screenW, screenH,   // kích thước vẽ ra (đích) → full màn hình
            gw, gh,             // region lấy mẫu = toàn bộ GIF
            gw, gh              // kích thước texture gốc
        );

        int dimAlpha = (int)(double) dimOverlay.getValue();
        if (dimAlpha > 0) {
            context.fill(0, 0, screenW, screenH, new Color(0, 0, 0, dimAlpha).getRGB());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HISTORY LOGIC (MRU: dùng gần nhất nằm cuối file)
    // ─────────────────────────────────────────────────────────────

    private void loadHistory() {
        gifHistory.clear();
        try {
            if (HISTORY_FILE.exists()) {
                List<String> lines = Files.readAllLines(HISTORY_FILE.toPath());
                for (String line : lines) {
                    String clean = line.trim();
                    if (!clean.isEmpty() && !gifHistory.contains(clean)) gifHistory.add(clean);
                }
                if (!gifHistory.isEmpty()) historyIndex = gifHistory.size() - 1;
            }
        } catch (Exception ignored) {}
    }

    private void saveToHistory(String url) {
        // Đưa url lên vị trí "mới dùng nhất" = cuối danh sách.
        gifHistory.remove(url);
        gifHistory.add(url);
        historyIndex = gifHistory.size() - 1;
        try {
            Files.writeString(HISTORY_FILE.toPath(), String.join("\n", gifHistory));
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────
    // TICK LOGIC (nút chuyển GIF / Load Clipboard)
    // ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (loadClipboard.getValue()) {
            loadClipboard.setValue(false);
            String clipboard = mc.keyboard.getClipboard();
            if (clipboard != null && clipboard.startsWith("http")) {
                if (clipboard.equals(currentUrl)) {
                    msg(mc, "§eLink này đang được dùng rồi!");
                } else {
                    currentUrl = clipboard;
                    loadGifAsync(clipboard);
                }
            } else {
                msg(mc, "§cClipboard không có link http!");
            }
        }

        if (prevGif.getValue()) {
            prevGif.setValue(false);
            if (!gifHistory.isEmpty()) {
                historyIndex = (historyIndex - 1 + gifHistory.size()) % gifHistory.size();
                currentUrl = gifHistory.get(historyIndex);
                loadGifAsync(currentUrl);
            } else msg(mc, "§cChưa có GIF nào được lưu!");
        }

        if (nextGif.getValue()) {
            nextGif.setValue(false);
            if (!gifHistory.isEmpty()) {
                historyIndex = (historyIndex + 1) % gifHistory.size();
                currentUrl = gifHistory.get(historyIndex);
                loadGifAsync(currentUrl);
            } else msg(mc, "§cChưa có GIF nào được lưu!");
        }
    }

    private void msg(MinecraftClient mc, String s) {
        if (mc.player != null)
            mc.player.sendMessage(net.minecraft.text.Text.literal("§d[GifGUI] " + s), false);
    }

    // ─────────────────────────────────────────────────────────────
    // DOWNLOAD & DECODE GIF
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
        mc.execute(() -> msg(mc, "§aĐang tải GIF..."));

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

                final int gifW = reader.getWidth(0), gifH = reader.getHeight(0);
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
                        gifWidth  = gifW;
                        gifHeight = gifH;
                        currentFrame = 0;
                        lastFrameTime = System.currentTimeMillis();
                        saveToHistory(rawUrl);
                        msg(mc, "§eTải xong! (" + gifFrames.size() + " frames)");
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        isLoading = false;
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                mc.execute(() -> {
                    msg(mc, "§cLỗi tải link! " + e.getMessage());
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
        currentFrame = 0;
    }
}