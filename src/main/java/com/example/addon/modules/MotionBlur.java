package com.example.addon.modules;

import com.example.addon.screens.SkiaHud;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * [Feature Request] Motion Blur (GitHub issue #4), referencing modrinth.com/mod/motionblur.
 * <p>
 * The first version of this module was a naive fixed-alpha accumulation blend -- same ghost
 * strength every frame regardless of framerate or whether the camera was even moving, which
 * reads as a permanent smear rather than motion blur (this was the reported problem). This
 * version fixes both of the actual algorithmic gaps:
 * <ol>
 *   <li><b>Frame-rate-independent decay</b>: the ghost's retention is computed from elapsed
 *       wall time (an exponential time-constant, {@code Trail}), not a fixed per-draw alpha --
 *       at 240fps the ghost decays across 4x as many frames as at 60fps, but the same real
 *       amount of TIME, so the trail is the same visual length either way.</li>
 *   <li><b>Motion-adaptive strength</b>: blur amount is scaled every frame by the camera's
 *       actual angular + positional velocity (yaw/pitch delta and eye-position delta since the
 *       last frame, normalized against reference turn/move speeds). Standing still and looking
 *       around slowly renders perfectly sharp; a fast flick-turn or sprint blurs -- this is the
 *       real-motion-vector-free approximation every camera-only motion blur mod (including the
 *       referenced one) uses, since vanilla exposes no per-pixel/per-object motion vectors to
 *       hook into.</li>
 * </ol>
 * Still hooked into SkiaHud's end-of-frame surface (same GPU path MusicHUD/LiquidGlassHud
 * already use) rather than a new post-chain shader. Known remaining limitation: this draws
 * over the FULLY COMPOSITED frame (world + HUD together, no separate world-only render
 * target exists to blend against in this MC version's deferred/extract-then-render
 * pipeline) -- so the effect is skipped entirely whenever a screen is open (menu/inventory)
 * to at least keep GUIs sharp, but the in-game HUD (crosshair/hotbar) still gets swept up in
 * the blur along with the world during motion, same as before.
 */
public class MotionBlur extends AddonModule implements SkiaHud.Drawer {
    public static final MotionBlur INSTANCE = new MotionBlur();

    public final SliderOption strength = new SliderOption(this, "Strength",
        "Maximum ghost blend at full motion. 0 = off.", 0.55, 0.0, 0.95, 0.01);
    public final SliderOption trailMs = new SliderOption(this, "Trail",
        "Decay time constant in ms -- how long the ghost takes to fade, independent of framerate.", 120.0, 10.0, 500.0, 5.0);
    public final SliderOption turnSensitivity = new SliderOption(this, "TurnSensitivity",
        "Camera turn speed (deg/sec) that reaches full blur strength.", 260.0, 30.0, 1000.0, 10.0);
    public final SliderOption moveSensitivity = new SliderOption(this, "MoveSensitivity",
        "Movement speed (blocks/sec) that reaches full blur strength.", 7.0, 1.0, 40.0, 0.5);

    private Image prevSnapshot;
    private long lastFrameNanos = 0;
    private Vec3 lastCamPos;
    private float lastYaw, lastPitch;

    private MotionBlur() {
        super("MotionBlur", "Camera-motion-adaptive full-screen accumulation blur / smear trail.");
    }

    @Override
    public void onEnable() {
        SkiaHud.register(this);
        lastFrameNanos = 0;
        lastCamPos = null;
    }

    @Override
    public void onDisable() {
        SkiaHud.unregister(this);
        releaseSnapshot();
    }

    private void releaseSnapshot() {
        if (prevSnapshot != null) {
            prevSnapshot.close();
            prevSnapshot = null;
        }
    }

    /** 0 (still) .. 1 (at/above sensitivity thresholds) -- the stronger of turn-speed or move-speed this frame. */
    private float motionAmount(Minecraft mc, double dtSec) {
        if (mc.player == null || mc.gameRenderer == null) return 0f;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        float yaw = camera.yRot();
        float pitch = camera.xRot();

        if (lastCamPos == null) {
            lastCamPos = camPos;
            lastYaw = yaw;
            lastPitch = pitch;
            return 0f;
        }

        double moveSpeed = camPos.distanceTo(lastCamPos) / dtSec; // blocks/sec
        double yawDelta = Math.abs(Mth.wrapDegrees(yaw - lastYaw));
        double pitchDelta = Math.abs(pitch - lastPitch);
        double turnSpeed = Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta) / dtSec; // deg/sec

        lastCamPos = camPos;
        lastYaw = yaw;
        lastPitch = pitch;

        float moveAmount = (float) Mth.clamp(moveSpeed / moveSensitivity.getValue(), 0.0, 1.0);
        float turnAmount = (float) Mth.clamp(turnSpeed / turnSensitivity.getValue(), 0.0, 1.0);
        return Math.max(moveAmount, turnAmount);
    }

    @Override
    public void draw(DirectContext ctx, Canvas canvas, int fbW, int fbH) {
        float maxStrength = (float) (double) strength.getValue();
        Minecraft mc = Minecraft.getInstance();

        long now = System.nanoTime();
        double dtSec = lastFrameNanos == 0 ? 1.0 / 60.0 : (now - lastFrameNanos) / 1.0E9;
        lastFrameNanos = now;
        // Clamp dt so a lag spike/alt-tab doesn't register as one giant instantaneous motion.
        dtSec = Mth.clamp(dtSec, 1.0 / 480.0, 0.25);

        float motion = motionAmount(mc, dtSec);

        if (maxStrength <= 0.001f || mc.screen != null || mc.player == null) {
            // Screen open or effect disabled -- stay sharp, but keep the trail frozen rather
            // than discarded, so closing a menu mid-motion doesn't restart the blur from zero.
            if (maxStrength <= 0.001f) releaseSnapshot();
            return;
        }

        if (prevSnapshot != null && motion > 0.001f) {
            double decay = Math.exp(-dtSec * 1000.0 / trailMs.getValue());
            float alpha = (float) Mth.clamp(decay * motion * maxStrength, 0.0, 0.92);
            if (alpha > 0.001f) {
                try (Paint paint = new Paint()) {
                    paint.setAlphaf(alpha);
                    canvas.drawImageRect(prevSnapshot, Rect.makeWH(fbW, fbH), paint);
                }
            }
        }

        // Re-snapshot AFTER blending the ghost in, so next frame's trail includes this
        // frame's own blend (the recursive accumulation that makes the smear last more than
        // one frame). One extra GPU-side surface copy per frame -- acceptable for an opt-in
        // full-screen effect.
        Image fresh = canvas.getSurface() != null ? canvas.getSurface().makeImageSnapshot() : null;
        if (fresh != null) {
            releaseSnapshot();
            prevSnapshot = fresh;
        }
    }
}
