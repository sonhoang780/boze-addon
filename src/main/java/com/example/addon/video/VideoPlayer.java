package com.example.addon.video;

import net.minecraft.client.texture.NativeImage;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes a video file with JavaCV/FFmpeg on a background daemon thread.
 *
 * Two modes:
 *   • Non-streaming (default): pre-decodes up to maxFrames into a List<NativeImage>
 *     for random-access playback via getFrame(idx). Used for intro (plays once, short).
 *   • Streaming: bounded BlockingQueue; the BG thread blocks when full, so memory stays
 *     bounded regardless of video length. Looping is handled in the decode thread by
 *     re-opening the file after EOF. Consumer calls pollStreamFrame() sequentially.
 *     Used for the looping background video (long clip, no random-access needed).
 *
 * Frame conversion: FFmpeg outputs RGBA directly (setPixelFormat AV_PIX_FMT_RGBA).
 * toNativeImage() does a single bulk MemoryUtil.memCopy from the RGBA ByteBuffer to
 * NativeImage's native memory — no per-pixel Java loop. Falls back to a pixel loop
 * if reflection on NativeImage's pointer field fails.
 */
public class VideoPlayer {

    private final int maxW;
    private final int maxH;
    private final int maxFrames;
    private final String id;
    private final boolean looping;

    // Non-streaming mode: pre-decoded frames for random access.
    private final List<NativeImage> frames = new ArrayList<>();
    private final Object lock = new Object();

    // Streaming mode: bounded producer-consumer queue.
    private final boolean streamingMode;
    private final LinkedBlockingQueue<NativeImage> streamQueue;

    private volatile boolean decodeFinished = false;
    private volatile boolean disposing     = false;
    private volatile boolean error         = false;

    private long startMs = -1;
    private volatile boolean playing  = false;
    private volatile boolean finished = false;
    private volatile double  fps      = 30.0;

    // ── Bulk-copy fast path ──────────────────────────────────────────────────
    // Reflect on NativeImage's native memory pointer (the only long field) so
    // we can do a single MemoryUtil.memCopy per frame instead of 2M setColorArgb
    // calls. Initialised once; null means fallback to pixel loop.
    private static final Field NI_POINTER;
    static {
        Field f = null;
        try {
            for (Field field : NativeImage.class.getDeclaredFields()) {
                if (field.getType() == long.class) {
                    field.setAccessible(true);
                    f = field;
                    break;
                }
            }
            System.err.println("[BozeMenu] VideoPlayer: NativeImage pointer field "
                + (f != null ? "found (" + f.getName() + ")" : "NOT found — using pixel fallback"));
        } catch (Exception e) {
            System.err.println("[BozeMenu] VideoPlayer: NativeImage reflect failed — " + e);
        }
        NI_POINTER = f;
    }

    /** Non-streaming: pre-decode up to maxFrames frames for random-access playback. */
    public VideoPlayer(String id, boolean looping, int maxW, int maxH, int maxFrames) {
        this.id          = id;
        this.looping     = looping;
        this.maxW        = maxW;
        this.maxH        = maxH;
        this.maxFrames   = maxFrames;
        this.streamingMode = false;
        this.streamQueue   = null;
    }

    /**
     * Streaming: memory-bounded continuous decode.
     * BG thread blocks when the queue is full; seamlessly re-opens the file for looping.
     */
    public VideoPlayer(String id, boolean looping, int maxW, int maxH,
                       int streamBufferFrames, boolean streaming) {
        this.id          = id;
        this.looping     = looping;
        this.maxW        = maxW;
        this.maxH        = maxH;
        this.maxFrames   = Integer.MAX_VALUE; // no cap in streaming mode
        this.streamingMode = true;
        this.streamQueue   = new LinkedBlockingQueue<>(streamBufferFrames);
    }

    /** Non-streaming shorthand: 1280×720, 240 frames. */
    public VideoPlayer(String id, boolean looping) {
        this(id, looping, 1280, 720, 240);
    }

    public void startDecoding(File file, Runnable onFirstFrame) {
        Thread t = new Thread(() -> {
            boolean firstNotified = false;
            do {
                FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file);
                grabber.setImageWidth(maxW);
                grabber.setImageHeight(maxH);
                // Request RGBA output directly from FFmpeg — avoids Java2DFrameConverter
                // and the intermediate BufferedImage entirely.
                grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
                try {
                    if (!firstNotified) {
                        System.err.println("[BozeMenu] VideoPlayer '" + id + "' decoding: " + file.getAbsolutePath());
                    }
                    grabber.start();
                    if (!firstNotified) {
                        double detected = grabber.getVideoFrameRate();
                        if (detected >= 1.0 && detected <= 200.0) fps = detected;
                        System.err.println("[BozeMenu] VideoPlayer '" + id + "' @ "
                            + grabber.getImageWidth() + "x" + grabber.getImageHeight() + " " + fps + "fps");
                    }
                    int loopDecoded = 0;
                    Frame f;
                    while (!disposing && loopDecoded < maxFrames && (f = grabber.grabImage()) != null) {
                        if (f.image == null || f.image.length == 0) continue;
                        if (!(f.image[0] instanceof ByteBuffer)) continue;
                        NativeImage ni = toNativeImage(f);
                        if (ni == null) continue;
                        if (streamingMode) {
                            boolean offered = false;
                            while (!disposing && !offered) {
                                try { offered = streamQueue.offer(ni, 50, TimeUnit.MILLISECONDS); }
                                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                            }
                            if (!offered) { ni.close(); break; }
                        } else {
                            synchronized (lock) { frames.add(ni); }
                        }
                        loopDecoded++;
                        if (!firstNotified) {
                            firstNotified = true;
                            if (onFirstFrame != null) onFirstFrame.run();
                        }
                    }
                } catch (Throwable e) {
                    error = true;
                    System.err.println("[BozeMenu] VideoPlayer '" + id + "' DECODE FAILED: " + e);
                    e.printStackTrace();
                } finally {
                    try { grabber.stop(); grabber.release(); } catch (Exception ignored) {}
                }
                // Streaming+looping: seamlessly re-open file from the beginning.
            } while (streamingMode && looping && !disposing);

            if (!streamingMode) {
                System.err.println("[BozeMenu] VideoPlayer '" + id + "' pre-decoded " + frames.size() + " frames");
            }
            decodeFinished = !looping;
        }, "VideoDecoder-" + id);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Converts an RGBA Frame from FFmpeg into a NativeImage.
     *
     * Fast path: single MemoryUtil.memCopy (bulk native memcpy) from the RGBA ByteBuffer
     * to NativeImage's native memory — O(1) JNI calls regardless of pixel count.
     * Fallback: per-pixel loop (slow, used only if reflection fails).
     */
    private static NativeImage toNativeImage(Frame frame) {
        ByteBuffer src = (ByteBuffer) frame.image[0];
        int w      = frame.imageWidth;
        int h      = frame.imageHeight;
        // imageStride is in bytes for a ByteBuffer; default to w*4 if not set.
        int stride = frame.imageStride > 0 ? frame.imageStride : w * 4;
        if (w <= 0 || h <= 0 || src == null || !src.isDirect()) return null;

        NativeImage ni = new NativeImage(NativeImage.Format.RGBA, w, h, false);

        // ── Fast path: bulk memcpy ──────────────────────────────────────────
        if (NI_POINTER != null) {
            try {
                long dst = (long) NI_POINTER.get(ni);
                long srcBase = MemoryUtil.memAddress(src);
                if (stride == w * 4) {
                    // Contiguous RGBA rows — single copy.
                    MemoryUtil.memCopy(srcBase, dst, (long) w * h * 4);
                } else {
                    // Rows have alignment padding — copy row by row.
                    long rowBytes = (long) w * 4;
                    for (int y = 0; y < h; y++) {
                        MemoryUtil.memCopy(srcBase + (long) y * stride,
                                           dst     + (long) y * rowBytes,
                                           rowBytes);
                    }
                }
                return ni;
            } catch (Exception e) {
                System.err.println("[BozeMenu] VideoPlayer bulk copy failed, falling back: " + e);
                // Fall through to pixel loop below.
            }
        }

        // ── Fallback: per-pixel from RGBA ByteBuffer ────────────────────────
        for (int y = 0; y < h; y++) {
            int rowOff = y * stride;
            for (int x = 0; x < w; x++) {
                int off = rowOff + x * 4;
                int r = src.get(off)     & 0xFF;
                int g = src.get(off + 1) & 0xFF;
                int b = src.get(off + 2) & 0xFF;
                ni.setColorArgb(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        return ni;
    }

    public void play() {
        startMs  = System.currentTimeMillis();
        playing  = true;
        finished = false;
    }

    public double  getVideoFps()       { return fps; }
    public boolean isStreamingMode()   { return streamingMode; }

    /** How many frames are currently queued (streaming mode only). */
    public int getStreamQueueSize() {
        if (!streamingMode || streamQueue == null) return 0;
        return streamQueue.size();
    }

    /**
     * Streaming mode only: returns the next frame from the queue, or null if the queue
     * is empty (decode hasn't caught up yet). Caller must close the returned NativeImage
     * after its pixel data has been copied to a texture.
     */
    public NativeImage pollStreamFrame() {
        if (!streamingMode || !playing || disposing) return null;
        return streamQueue.poll();
    }

    /** Non-streaming: returns index of the frame to display now, or -1 if not ready. */
    public int getCurrentFrameIndex() {
        if (!playing || disposing) return -1;
        int available;
        synchronized (lock) { available = frames.size(); }
        if (available == 0) return -1;

        long elapsed = System.currentTimeMillis() - startMs;
        int  idx     = (int)(elapsed * fps / 1000.0);

        if (looping) return idx % available;
        if (decodeFinished && idx >= available) {
            finished = true;
            return available - 1;
        }
        return Math.min(idx, available - 1);
    }

    public NativeImage getFrame(int idx) {
        synchronized (lock) {
            if (idx < 0 || idx >= frames.size()) return null;
            return frames.get(idx);
        }
    }

    public int     getFrameCount()    { synchronized (lock) { return frames.size(); } }
    public boolean isPlaying()        { return playing; }
    public boolean isFinished()       { return finished; }
    public boolean isDecodeFinished() { return decodeFinished; }
    public boolean hasError()         { return error; }

    public void dispose() {
        disposing = true;
        playing   = false;
        if (streamingMode) {
            List<NativeImage> leftover = new ArrayList<>();
            streamQueue.drainTo(leftover);
            leftover.forEach(ni -> { try { ni.close(); } catch (Exception ignored) {} });
        } else {
            synchronized (lock) {
                for (NativeImage ni : frames) { try { ni.close(); } catch (Exception ignored) {} }
                frames.clear();
            }
        }
    }
}
