package com.example.addon.render;

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

    private volatile int loadGeneration = 0;
    
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
        loadGeneration++; // cancel any gif decode still running from a previous loadImage call
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
        final int myGeneration = loadGeneration;

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

                // Skip the PNG encode/decode round-trip the old code did per frame
                // (compositeFrame -> ImageIO.write PNG -> hop threads -> NativeImage.read
                // PNG) -- DEFLATE compression of every frame was the actual cost behind
                // ~1min loads for heavy GIFs. Copy composited pixels straight into a
                // NativeImage instead; it's plain CPU pixel data, safe to build off the
                // render thread, only the DynamicTexture/GL upload needs mcExecute.
                List<NativeImage> decodedFrames = new ArrayList<>();
                int[] row = new int[gifW];
                for (int i = 0; i < numFrames; i++) {
                    if (myGeneration != loadGeneration) {
                        // A newer loadImage()/mode switch superseded this decode; stop wasting work.
                        g2.dispose(); reader.dispose(); iis.close();
                        for (NativeImage ni : decodedFrames) ni.close();
                        isLoading = false;
                        return;
                    }
                    g2.drawImage(reader.read(i), 0, 0, null);
                    NativeImage img = new NativeImage(NativeImage.Format.RGBA, gifW, gifH, false);
                    for (int y = 0; y < gifH; y++) {
                        canvas.getRGB(0, y, gifW, 1, row, 0, gifW);
                        for (int x = 0; x < gifW; x++) {
                            int argb = row[x];
                            int abgr = (argb & 0xFF000000) | ((argb & 0xFF) << 16) | (argb & 0x00FF00) | ((argb >> 16) & 0xFF);
                            img.setPixelABGR(x, y, abgr);
                        }
                    }
                    decodedFrames.add(img);
                }
                g2.dispose(); reader.dispose(); iis.close();

                mcExecute(() -> {
                    if (myGeneration != loadGeneration) {
                        // Superseded while hopping back to the main thread -- discard, don't clobber
                        // whatever the newer selection (image/shader/different gif) already set.
                        for (NativeImage ni : decodedFrames) ni.close();
                        isLoading = false;
                        return;
                    }
                    clearGif();
                    try {
                        for (int i = 0; i < decodedFrames.size(); i++) {
                            final int fi = i;
                            DynamicTexture tex = new DynamicTexture(() -> "betterchams_gif_" + fi, decodedFrames.get(i));
                            gifFrames.add(tex);
                        }
                        isGif = true;
                        currentFrame = 0;
                        playDirection = 1;
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
    
    private int playDirection = 1;

    public void tick(double frameDelayMs) {
        tick(frameDelayMs, false);
    }

    public void tick(double frameDelayMs, boolean bounce) {
        if (!isGif || gifFrames.isEmpty() || !hasImage) return;

        long now = System.currentTimeMillis();
        long delay = (long) frameDelayMs;
        if (now - lastFrameTime >= delay) {
            lastFrameTime = now;

            if (bounce && gifFrames.size() > 1) {
                // Ping-pong instead of wrapping: hides the seam where a looping GIF
                // visibly jump-cuts from its last frame back to its first.
                int next = currentFrame + playDirection;
                if (next >= gifFrames.size()) {
                    playDirection = -1;
                    next = gifFrames.size() - 2;
                } else if (next < 0) {
                    playDirection = 1;
                    next = 1;
                }
                currentFrame = next;
            } else {
                currentFrame = (currentFrame + 1) % gifFrames.size();
            }

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
