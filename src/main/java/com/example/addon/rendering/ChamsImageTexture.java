package com.example.addon.rendering;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ChamsImageTexture extends AbstractTexture {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChamsImageTexture.class);

    private DynamicTexture inner;
    private boolean hasImage = false;
    private boolean isLoading = false;
    
    private final List<DynamicTexture> gifFrames = new ArrayList<>();
    private boolean isGif = false;
    private int currentFrame = 0;
    private long lastFrameTime = 0;

    public ChamsImageTexture() {}

    public void init() {
        if (inner != null) return;
        inner = new DynamicTexture("betterchams-fill-blank", 1, 1, false);
        syncFromInner();
    }

    public void loadImage(Path path) {
        if (path.toString().toLowerCase().endsWith(".gif")) {
            loadGifAsync(path);
        } else {
            loadStaticImage(path);
        }
    }

    private void loadStaticImage(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            NativeImage img = NativeImage.read(is);
            DynamicTexture newInner = new DynamicTexture(() -> "betterchams-fill", img);
            mcExecute(() -> {
                boolean wasGifFrame = gifFrames.contains(inner);
                clearGif();
                if (inner != null && !wasGifFrame) inner.close();
                inner = newInner;
                hasImage = true;
                syncFromInner();
            });
        } catch (Exception e) {
            LOGGER.error("BetterChams: failed to load image {}", path, e);
        }
    }

    private void loadGifAsync(Path path) {
        if (isLoading) return;
        isLoading = true;
        hasImage = false;
        
        CompletableFuture.runAsync(() -> {
            try {
                byte[] gifBytes = Files.readAllBytes(path);
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

                mcExecute(() -> {
                    clearGif();
                    try {
                        for (int i = 0; i < rawFrames.size(); i++) {
                            final int fi = i;
                            NativeImage img = NativeImage.read(new ByteArrayInputStream(rawFrames.get(i)));
                            DynamicTexture tex = new DynamicTexture(() -> "betterchams_gif_" + fi, img);
                            gifFrames.add(tex);
                        }
                        isGif = true;
                        currentFrame = 0;
                        if (!gifFrames.isEmpty()) {
                            if (inner != null && !gifFrames.contains(inner)) inner.close();
                            inner = gifFrames.get(0);
                            syncFromInner();
                            hasImage = true;
                        }
                    } catch (Exception e) {
                        LOGGER.error("BetterChams: failed to parse GIF frames", e);
                    } finally {
                        isLoading = false;
                    }
                });
            } catch (Exception e) {
                LOGGER.error("BetterChams: failed to load GIF {}", path, e);
                isLoading = false;
            }
        });
    }
    
    public void tick(double frameDelayMs) {
        if (!isGif || gifFrames.isEmpty() || !hasImage) return;
        
        long now = System.currentTimeMillis();
        long delay = (long) frameDelayMs;
        if (now - lastFrameTime >= delay) {
            currentFrame = (currentFrame + 1) % gifFrames.size();
            lastFrameTime = now;
            
            inner = gifFrames.get(currentFrame);
            syncFromInner();
        }
    }

    private void mcExecute(Runnable r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.execute(r);
        else r.run();
    }

    private void clearGif() {
        for (DynamicTexture tex : gifFrames) {
            if (tex != inner) tex.close();
        }
        gifFrames.clear();
        isGif = false;
    }

    private int externalTextureId = -1;

    public void loadSolidColor(int argb) {
        mcExecute(() -> {
            boolean wasGifFrame = gifFrames.contains(inner);
            clearGif();
            if (inner != null && !wasGifFrame) inner.close();
            com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(1, 1, false);
            img.setPixelABGR(0, 0, argb);
            inner = new DynamicTexture(() -> "betterchams_solid", img);
            syncFromInner();
        });
    }

    public void resizeForShader(int width, int height) {
        if (inner == null || inner.getPixels() == null || inner.getPixels().getWidth() != width || inner.getPixels().getHeight() != height) {
            mcExecute(() -> {
                if (inner != null && !gifFrames.contains(inner)) inner.close();
                com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(width, height, false);
                inner = new DynamicTexture(() -> "betterchams_shader", img);
                hasImage = true;   // ← FIX: mark as ready so fill renders on startup
                syncFromInner();
            });
        }
    }

    public int getRawTextureId() {
        if (this.texture instanceof com.mojang.blaze3d.opengl.GlTexture) {
            return ((com.mojang.blaze3d.opengl.GlTexture) this.texture).glId();
        }
        return -1;
    }

    private void syncFromInner() {
        if (inner != null) {
            this.texture     = inner.getTexture();
            this.textureView = inner.getTextureView();
            this.sampler     = inner.getSampler();
        }
    }

    public boolean hasImage() {
        return hasImage && inner != null;
    }

    @Override
    public void close() {
        clearGif();
        if (inner != null) {
            inner.close();
            inner = null;
        }
        super.close();
    }
}
