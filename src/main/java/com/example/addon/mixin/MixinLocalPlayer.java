package com.example.addon.mixin;

import com.example.addon.modules.FakeFly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer {

    /**
     * applyInput() smooths xBob/yBob 50% per tick toward getXRot()/getYRot() (the
     * no-arg overload) — that lag is what ItemInHandRenderer.renderHandsWithItems
     * later turns into the held-item's lean/sway rotation whenever the camera turns.
     *
     * While FakeFly is flying, applyInput() runs in the Pre/Post window where
     * FakeFly.prepDirection() has temporarily overwritten the entity's REAL rotation
     * fields with the flight/movement direction (targetPitch/targetYaw) — and
     * getXRot()/getYRot() (no-arg) read those raw fields directly, unlike
     * getXRot(float)/getYRot(float) which MixinEntity intercepts back to the saved
     * camera angle. So xBob/yBob were tracking the MOVEMENT direction, not the
     * camera — that's why the hand followed the WASD direction instead of staying
     * still: snapping xBob/yBob to getXRot()/getYRot() (as the first version of this
     * fix did) just removed the lag while still tracking the wrong angle. Using
     * FakeFly's saved camera angle here instead fixes that.
     */
    @Inject(method = "applyInput", at = @At("TAIL"), require = 0)
    private void fakefly$stillHandBob(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player == self
                && FakeFly.INSTANCE.getState()
                && (FakeFly.INSTANCE.isFlying() || self.isFallFlying())) {
            if (FakeFly.cameraOverrideActive) {
                self.xBob = FakeFly.savedCameraPitch;
                self.yBob = FakeFly.savedCameraYaw;
            } else {
                self.xBob = self.getXRot();
                self.yBob = self.getYRot();
            }
        }
    }
}
