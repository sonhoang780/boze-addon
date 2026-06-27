package com.example.addon.mixin;

import com.example.addon.modules.EBounce;
import com.example.addon.modules.EBouncePlus;
import com.example.addon.modules.FakeFly;
import com.example.addon.modules.VanillaEBounce;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While FakeFly's cameraOverrideActive is true (between Pre setting targetPitch/Yaw and
 * Post restoring savedCamera*), intercept Entity.getPitch/getYaw for the local player.
 *
 * This ensures the first-person arm renderer, HeldItemRenderer, and Camera.update() all
 * see the real camera rotation — not the artificial flight direction — so the hand/weapon
 * stays in its natural position and never flings toward the flight pitch.
 *
 * sendMovementPackets() uses the raw entity.yaw / entity.pitch fields directly (not these
 * methods), so the server still receives the targetYaw for correct rocket boost direction.
 */
@Mixin(Entity.class)
public abstract class MixinEntity {

    @Inject(method = "getXRot(F)F", at = @At("HEAD"), cancellable = true)
    private void fakefly$overridePitch(float tickDelta, CallbackInfoReturnable<Float> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player != (Object) this) return;
        if (FakeFly.cameraOverrideActive)       { cir.setReturnValue(FakeFly.savedCameraPitch);       return; }
        if (EBounce.pitchOverrideActive)         { cir.setReturnValue(EBounce.savedCameraPitch);       return; }
        if (VanillaEBounce.pitchOverrideActive)  { cir.setReturnValue(VanillaEBounce.savedCameraPitch); return; }
        if (EBouncePlus.pitchOverrideActive)     { cir.setReturnValue(EBouncePlus.savedCameraPitch);   return; }
    }
    
    @Inject(method = "getYRot(F)F", at = @At("HEAD"), cancellable = true)
    private void fakefly$overrideYaw(float tickDelta, CallbackInfoReturnable<Float> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (FakeFly.cameraOverrideActive && mc != null && mc.player == (Object) this) {
            cir.setReturnValue(FakeFly.savedCameraYaw);
        }
    }
}
