package com.example.addon.mixin;

import com.example.addon.modules.ControlRocket;
import com.example.addon.modules.NoSlow;
import com.example.addon.modules.Velocity;
import com.example.addon.util.EarlyTickHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * The actual physics-removal core, ported from ThunderHack-Reborn's
     * MixinClientPlayerEntity#tickMovementHook -- a @Redirect on the SAME isUsingItem() call
     * modifyInput() guards its itemUseSpeedMultiplier() scale with (verified via javap/
     * decompile, 26.1.2: "if (this.isUsingItem() && !this.isPassenger()) newInput =
     * newInput.scale(this.itemUseSpeedMultiplier());"). Forcing this call to return false
     * skips the scale entirely -- real isUsingItem() elsewhere (animation, eating progress,
     * server sync) is untouched, only THIS call site lies. Gated by NoSlow.canNoSlow() so the
     * per-item/per-mode toggles (Food/Shield/Projectiles/MainHand/...) still apply exactly as
     * in the original. Matrix3 is excluded (canNoSlow() returns false for it in the original
     * too) since it replaces the scale with its own ground/diagonal/air curve instead, via
     * noSlow$matrix3 below.
     */
    @Redirect(method = "modifyInput", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"), require = 0)
    private boolean noSlow$suppressUsingItem(LocalPlayer self) {
        boolean real = self.isUsingItem();
        if (!real) return false;
        if (Minecraft.getInstance().player == self && NoSlow.INSTANCE.getState() && NoSlow.INSTANCE.shouldCancelSlowdown()) return false;
        return true;
    }

    /**
     * Velocity's NoPush-Blocks: real Mojmap name for the "pushOutOfBlocks" mechanic Kallean/
     * ThunderHack-Reborn hook on older versions (their real Yarn-mapped target name) -- verified
     * on 26.1.2 via raw constant-pool string search (present, same (double,double) signature),
     * not a name-guess. Cancelling HEAD stops LocalPlayer's own squeeze-escape nudge.
     */
    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true, require = 0)
    private void velocity$noPushBlocks(double x, double z, CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (Minecraft.getInstance().player == self && Velocity.INSTANCE.getState() && Velocity.INSTANCE.noPushBlocks.getValue()) {
            ci.cancel();
        }
    }

    /**
     * modifyInput(Vec2) (private) is where LocalPlayer applies itemUseSpeedMultiplier()'s
     * 0.2x to the raw movement input while isUsingItem() -- verified via javap (26.1.2).
     * NoSlow's Matrix3 mode (ported from ThunderHack-Reborn) has no Boze input-event
     * equivalent (EventInput only exposes boolean forward/back/left/right, not a scalable
     * float vector like Yarn's player.input.movementVector), so it's ported here instead:
     * override the return value with the same ground/diagonal/air multiplier curve, computed
     * from the untouched method parameter (the pre-slowdown raw input).
     */
    @Inject(method = "modifyInput", at = @At("RETURN"), cancellable = true, require = 0)
    private void noSlow$matrix3(net.minecraft.world.phys.Vec2 rawInput, CallbackInfoReturnable<net.minecraft.world.phys.Vec2> cir) {
        if (!NoSlow.INSTANCE.getState() || NoSlow.INSTANCE.mode.getValue() != NoSlow.Mode.Matrix3) return;
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!self.isUsingItem() || self.isFallFlying()) return;

        float mult;
        boolean diagonal = rawInput.x != 0 && rawInput.y != 0;
        if (self.onGround()) {
            mult = diagonal ? 0.35f : 0.5f;
        } else {
            mult = diagonal ? 0.47f : 0.67f;
        }
        cir.setReturnValue(rawInput.scale(5f).scale(mult));
    }
}
