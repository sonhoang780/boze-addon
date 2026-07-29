package com.example.addon.modules;

import com.example.addon.screens.SkiaHud;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

/**
 * [Feature Request] Motion Blur (GitHub issue #4): full-screen accumulation-blend trail,
 * the same technique real game engines use for cheap motion blur -- each frame draws last
 * frame's snapshot back over the new one at low alpha, then re-snapshots (frame N = new frame
 * + Strength*previousAccum, recursively). Hooked into SkiaHud (draws directly onto the FBO-0
 * surface at end of frame, same layer MusicHUD/LiquidGlassHud already use) rather than a
 * custom post-chain shader, since that GPU path is already proven working in this codebase.
 * <p>
 * Strength=0 disables the effect outright (also releases the held snapshot) without needing
 * a separate toggle -- the module's own enable/disable already gates whether this runs at all.
 */
public class MotionBlur extends AddonModule implements SkiaHud.Drawer {
    public static final MotionBlur INSTANCE = new MotionBlur();

    public final SliderOption strength = new SliderOption(this, "Strength",
        "How strongly the previous frame bleeds into the new one. 0 = off.", 0.35, 0.0, 0.9, 0.01);

    private Image prevSnapshot;

    private MotionBlur() {
        super("MotionBlur", "Full-screen accumulation-blend motion blur / smear trail.");
    }

    @Override
    public void onEnable() {
        SkiaHud.register(this);
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

    @Override
    public void draw(DirectContext ctx, Canvas canvas, int fbW, int fbH) {
        float alpha = (float) (double) strength.getValue();
        if (alpha <= 0.001f) {
            releaseSnapshot();
            return;
        }

        if (prevSnapshot != null) {
            try (Paint paint = new Paint()) {
                paint.setAlphaf(alpha);
                canvas.drawImageRect(prevSnapshot, Rect.makeWH(fbW, fbH), paint);
            }
        }

        // Re-snapshot AFTER blending the ghost in, so next frame's trail includes this
        // frame's own blend (the recursive accumulation that makes the smear last more
        // than one frame). One extra GPU-side surface copy per frame -- acceptable for an
        // opt-in full-screen effect, not run when Strength is 0.
        Image fresh = canvas.getSurface() != null ? canvas.getSurface().makeImageSnapshot() : null;
        if (fresh != null) {
            releaseSnapshot();
            prevSnapshot = fresh;
        }
    }
}
