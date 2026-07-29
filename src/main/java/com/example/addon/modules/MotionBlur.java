package com.example.addon.modules;

import com.mojang.blaze3d.platform.NativeImage;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * [Feature Request] Motion Blur (GitHub issue #4), referencing modrinth.com/mod/motionblur.
 * <p>
 * That mod (Satin's ShaderEffectRenderCallback + a vanilla-style PostChain JSON: ping-pong
 * render targets, GPU shader {@code mix(Curr, Prev, BlendFactor)}) doesn't port as-is -- this
 * MC build's PostChain is a different, newer Codec+FrameGraphBuilder-based system
 * (PostChainConfig record types, verified via javap; no ManagedShaderEffect/
 * setUniformValue API). This ports the same real technique to THIS engine's actual
 * mechanism instead of faking it in Java/Skija (the first version of this module drew a
 * Skija-borrowed full-frame snapshot back over the next frame -- a CPU/GPU-interop hack
 * that also caught the composited HUD in the blur along with the world):
 * <ul>
 *   <li>Real ping-pong GPU render targets ("blend" scratch + "prev", persistent=true --
 *       PostChainConfig$InternalTarget's persistent field, unused anywhere else in vanilla
 *       assets but exactly the primitive a temporal-accumulation effect needs) wired into
 *       the frame graph via MixinLevelRenderer, the same proven pattern this addon already
 *       uses for TungTungSahur's smoke and CustomSky.</li>
 *   <li>Runs at the WORLD-render stage (PostChain resolves onto {@code minecraft:main}
 *       before the HUD/GUI composites on top later in GameRenderer's separate extract/render
 *       passes) -- so, unlike the Skija version, the HUD is no longer swept into the blur.</li>
 *   <li>Per-frame blend factor is still computed on the Java side (frame-rate-independent
 *       exponential decay + camera turn/move-speed adaptive strength, same math as the
 *       previous version) and fed into the shader via a 1x1 data texture, the same
 *       DynamicTexture-per-frame-upload trick TungTungSahur uses to get dynamic values into
 *       a PostChain shader -- this PostChain implementation has no runtime-settable uniform
 *       API from Java, unlike the old ManagedShaderEffect the reference mod uses.</li>
 * </ul>
 */
public class MotionBlur extends AddonModule {
    public static final MotionBlur INSTANCE = new MotionBlur();

    public static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("example-addon", "motion_blur");

    // Post-effect "location": "example-addon:motionblurparams" resolves to the resource path
    // textures/effect/motionblurparams.png -- registering under the bare name binds the
    // sampler to the missing-texture fallback instead (see TungTungSahur.SMOKE_PARAMS_ID).
    private static final Identifier PARAMS_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/motionblurparams.png");
    private static DynamicTexture paramsTexture;

    public final SliderOption strength = new SliderOption(this, "Strength",
        "Maximum ghost blend at full motion. 0 = off.", 0.55, 0.0, 0.95, 0.01);
    public final SliderOption trailMs = new SliderOption(this, "Trail",
        "Decay time constant in ms -- how long the ghost takes to fade, independent of framerate.", 120.0, 10.0, 500.0, 5.0);
    public final SliderOption turnSensitivity = new SliderOption(this, "TurnSensitivity",
        "Camera turn speed (deg/sec) that reaches full blur strength.", 260.0, 30.0, 1000.0, 10.0);
    public final SliderOption moveSensitivity = new SliderOption(this, "MoveSensitivity",
        "Movement speed (blocks/sec) that reaches full blur strength.", 7.0, 1.0, 40.0, 0.5);

    private long lastFrameNanos = 0;
    private Vec3 lastCamPos;
    private float lastYaw, lastPitch;

    private MotionBlur() {
        super("MotionBlur", "Camera-motion-adaptive full-screen accumulation blur / smear trail.");
    }

    /** Call once from ExampleAddon.initialize(), same as TungTungSahur/CustomSky/BetterChams. */
    public static void registerTextures() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            paramsTexture = new DynamicTexture(() -> "motionblur-params", img);
            mc.getTextureManager().register(PARAMS_ID, paramsTexture);
        });
    }

    @Override
    public void onEnable() {
        // Reset motion tracking so re-enabling doesn't read a huge dt/delta from
        // whenever it was last on.
        lastFrameNanos = 0;
        lastCamPos = null;
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

    /** Called from MixinLevelRenderer right before addToFrame, every world-render frame while enabled. */
    public void updateParams(Minecraft mc) {
        if (paramsTexture == null || mc.player == null) return;
        NativeImage img = paramsTexture.getPixels();
        if (img == null) return;

        long now = System.nanoTime();
        double dtSec = lastFrameNanos == 0 ? 1.0 / 60.0 : (now - lastFrameNanos) / 1.0E9;
        lastFrameNanos = now;
        // Clamp dt so a lag spike/alt-tab doesn't register as one giant instantaneous motion.
        dtSec = Mth.clamp(dtSec, 1.0 / 480.0, 0.25);

        float motion = motionAmount(mc, dtSec);
        double decay = Math.exp(-dtSec * 1000.0 / trailMs.getValue());
        float maxStrength = (float) (double) strength.getValue();
        float alpha = (float) Mth.clamp(decay * motion * maxStrength, 0.0, 0.92);

        int v = Math.round(alpha * 255f);
        img.setPixel(0, 0, (0xFF << 24) | (v << 16) | (v << 8) | v);
        paramsTexture.upload();
    }
}
