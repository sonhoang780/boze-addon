package com.example.addon.mixin;

import com.example.addon.modules.ControlRocket;
import com.example.addon.util.EarlyTickHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer {

    /**
     * Dispatches EarlyTickHooks at the very HEAD of LocalPlayer#tick() -- runs before
     * vanilla's own physics/movement tick for that frame (onGround/gliding-flag resolution
     * included). Lets callers (e.g. EBouncePlus) run logic strictly before vanilla resolves
     * ground/gliding state for the frame -- lambda-client's GlideHandler force-flag takeoff
     * runs at TickEvent.Pre({-1000}), earlier than a normal tick handler; this is the
     * closest equivalent without an addon-wide event-priority system. The public
     * register/unregister API lives on EarlyTickHooks, not here -- Sponge Mixin rejects
     * non-private static methods inside an @Mixin class.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void earlyTick$dispatch(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (Minecraft.getInstance().player != self) return;
        EarlyTickHooks.dispatch();
    }

    /**
     * applyInput() smooths xBob/yBob 50% per tick toward getXRot()/getYRot() (the
     * no-arg overload) — that lag is what ItemInHandRenderer.renderHandsWithItems
     * later turns into the held-item's lean/sway rotation whenever the camera turns.
     *
     * While ControlRocket is flying, applyInput() runs in the Pre/Post window where
     * ControlRocket.prepDirection() has temporarily overwritten the entity's REAL rotation
     * fields with the flight/movement direction (targetPitch/targetYaw) — and
     * getXRot()/getYRot() (no-arg) read those raw fields directly, unlike
     * getXRot(float)/getYRot(float) which MixinEntity intercepts back to the saved
     * camera angle. So xBob/yBob were tracking the MOVEMENT direction, not the
     * camera — that's why the hand followed the WASD direction instead of staying
     * still: snapping xBob/yBob to getXRot()/getYRot() (as the first version of this
     * fix did) just removed the lag while still tracking the wrong angle. Using
     * ControlRocket's saved camera angle here instead fixes that.
     */
    @Inject(method = "applyInput", at = @At("TAIL"), require = 0)
    private void fakefly$stillHandBob(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player == self
                && ControlRocket.INSTANCE.getState()
                && (ControlRocket.INSTANCE.isFlying() || self.isFallFlying())) {
            if (ControlRocket.cameraOverrideActive) {
                self.xBob = ControlRocket.savedCameraPitch;
                self.yBob = ControlRocket.savedCameraYaw;
            } else {
                self.xBob = self.getXRot();
                self.yBob = self.getYRot();
            }
        }
    }
}
