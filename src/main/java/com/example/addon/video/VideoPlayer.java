package com.example.addon.video;

import net.minecraft.client.texture.NativeImage;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Decodes a video file with JavaCV/FFmpeg on a background daemon thread.
 *
 * Two modes:
 *   • Non-streaming (default): pre-decodes up to maxFrames into a List<NativeImage>
 *     for random-access playback via getFrame(idx).
 *   • Streaming: bounded BlockingQueue; BG thread blocks when full. Looping re-opens
 *     the file at EOF. Consumer calls pollStreamFrame() sequentially.
 *
 * Frame conversion: FFmpeg outputs RGBA directly (AV_PIX_FMT_RGBA). toNativeImage()
 * does a single bulk MemoryUtil.memCopy — no per-pixel Java loop. Falls back to pixel
 * loop if reflection on NativeImage's pointer field fails.
 *
 * Audio (streaming mode only): decode thread also grabs audio frames via grab() and
 * plays them through a javax.sound SourceDataLine. Controlled by setAudioEnabled().
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

    // ── Audio (streaming mode only) ──────────────────────────────────────────
    private volatile boolean audioEnabled = false;
    private SourceDataLine   audioLine    = null; // opened in decode thread, closed in dispose()
    private byte[]           audioBuf     = new byte[0]; // reusable; grows as needed

    // ── Bulk-copy fast path ──────────────────────────────────────────────────
    // Reflect on NativeImage's native memory pointer (the only long field) so we can
    // do a single MemoryUtil.memCopy per frame instead of 2M setColorArgb calls.
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
     * BG thread blocks when the queue is full; seamlessly re-opens file for looping.
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

                    if (streamingMode) {
                        // Streaming: use grab() to receive both video and audio frames.
                        while (!disposing && (f = grabber.grab()) != null) {
                            // Audio frame — always processed first, never blocked, keeps audio smooth.
                            if (f.samples != null && f.samples.length > 0 && f.samples[0] instanceof ShortBuffer) {
                                playAudioFrame(f, grabber);
                            }
                            // Video frame — non-blocking offer; drop frame if queue is full so
                            // the decode thread never stalls and audio stays uninterrupted.
                            if (f.image != null && f.image.length > 0 && f.image[0] instanceof ByteBuffer) {
                                NativeImage ni = toNativeImage(f);
                                if (ni != null) {
                                    if (streamQueue.offer(ni)) {
                                        loopDecoded++;
                                        if (!firstNotified) {
                                            firstNotified = true;
                                            if (onFirstFrame != null) onFirstFrame.run();
                                        }
                                    } else {
                                        ni.close(); // queue full — drop frame, audio keeps going
                                    }
                                }
                            }
                        }
                    } else {
                        // Non-streaming: grabImage() only — no audio needed for intro.
                        while (!disposing && loopDecoded < maxFrames && (f = grabber.grabImage()) != null) {
                            if (f.image == null || f.image.length == 0) continue;
                            if (!(f.image[0] instanceof ByteBuffer)) continue;
                            NativeImage ni = toNativeImage(f);
                            if (ni == null) continue;
                            synchronized (lock) { frames.add(ni); }
                            loopDecoded++;
                            if (!firstNotified) {
                                firstNotified = true;
                                if (onFirstFrame != null) onFirstFrame.run();
                            }
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
     * Fast path: single MemoryUtil.memCopy (bulk native memcpy).
     * Fallback: per-pixel loop if reflection fails.
     */
    private static NativeImage toNativeImage(Frame frame) {
        ByteBuffer src = (ByteBuffer) frame.image[0];
        int w      = frame.imageWidth;
        int h      = frame.imageHeight;
        int stride = frame.imageStride > 0 ? frame.imageStride : w * 4;
        if (w <= 0 || h <= 0 || src == null || !src.isDirect()) return null;

        NativeImage ni = new NativeImage(NativeImage.Format.RGBA, w, h, false);

        if (NI_POINTER != null) {
            try {
                long dst     = (long) NI_POINTER.get(ni);
                long srcBase = MemoryUtil.memAddress(src);
                if (stride == w * 4) {
                    MemoryUtil.memCopy(srcBase, dst, (long) w * h * 4);
                } else {
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
            }
        }

        // Fallback: per-pixel from RGBA ByteBuffer.
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

    /** Decodes one audio frame and writes samples to the SourceDataLine. */
    private void playAudioFrame(Frame f, FFmpegFrameGrabber grabber) {
        if (!audioEnabled) return;

        // Open line on first audio frame encountered.
        if (audioLine == null || !audioLine.isOpen()) {
            try {
                int rate = grabber.getSampleRate();
                int ch   = grabber.getAudioChannels();
                if (rate <= 0) rate = 44100;
                if (ch   <= 0) ch   = 2;
                AudioFormat fmt = new AudioFormat(rate, 16, ch, true, false);
                audioLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
                audioLine.open(fmt, rate * ch * 2); // ~1 s buffer
                audioLine.start();
            } catch (Exception e) {
                System.err.println("[BozeMenu] Audio open failed: " + e);
                return;
            }
        }
        if (!audioLine.isRunning()) audioLine.start();

        // FFmpeg may output planar audio (one ShortBuffer per channel) or packed
        // interleaved (all channels in samples[0]). Handle both cases.
        boolean planar = f.samples.length >= 2 && f.samples[1] instanceof ShortBuffer;
        if (planar) {
            // Planar stereo: interleave L and R into a single PCM stream.
            ShortBuffer left  = (ShortBuffer) f.samples[0];
            ShortBuffer right = (ShortBuffer) f.samples[1];
            left.rewind(); right.rewind();
            int n    = Math.min(left.remaining(), right.remaining());
            int need = n * 4; // 2 bytes × 2 channels per sample
            if (audioBuf.length < need) audioBuf = new byte[need];
            for (int i = 0, j = 0; i < n; i++, j += 4) {
                short l = left.get(), r = right.get();
                audioBuf[j]     = (byte)(l & 0xFF);
                audioBuf[j + 1] = (byte)((l >> 8) & 0xFF);
                audioBuf[j + 2] = (byte)(r & 0xFF);
                audioBuf[j + 3] = (byte)((r >> 8) & 0xFF);
            }
            audioLine.write(audioBuf, 0, need);
        } else {
            // Packed/interleaved (or mono): samples[0] already contains all channels.
            ShortBuffer sb = (ShortBuffer) f.samples[0];
            sb.rewind();
            int n    = sb.remaining();
            int need = n * 2;
            if (audioBuf.length < need) audioBuf = new byte[need];
            for (int i = 0, j = 0; i < n; i++, j += 2) {
                short s = sb.get();
                audioBuf[j]     = (byte)(s & 0xFF);
                audioBuf[j + 1] = (byte)((s >> 8) & 0xFF);
            }
            audioLine.write(audioBuf, 0, need);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void play() {
        startMs  = System.currentTimeMillis();
        playing  = true;
        finished = false;
    }

    /** Enable or disable audio playback. Safe to call from any thread. */
    public void setAudioEnabled(boolean enabled) {
        this.audioEnabled = enabled;
        if (!enabled && audioLine != null && audioLine.isOpen()) {
            audioLine.stop();
            audioLine.flush();
        } else if (enabled && audioLine != null && audioLine.isOpen() && !audioLine.isRunning()) {
            audioLine.start();
        }
    }

    public double  getVideoFps()       { return fps; }
    public boolean isStreamingMode()   { return streamingMode; }

    /** How many frames are currently queued (streaming mode only). */
    public int getStreamQueueSize() {
        if (!streamingMode || streamQueue == null) return 0;
        return streamQueue.size();
    }

    /**
     * Streaming mode only: returns the next frame from the queue, or null if empty.
     * Caller must close the NativeImage after uploading to GPU.
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
        // Close audio line first so the decode thread's audioLine.write() unblocks.
        if (audioLine != null) {
            try { audioLine.stop(); audioLine.flush(); audioLine.close(); } catch (Exception ignored) {}
            audioLine = null;
        }
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
