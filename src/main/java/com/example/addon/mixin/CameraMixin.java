package com.example.addon.mixin;

import com.example.addon.modules.SmoothMotion;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private Vec3d pos;
    @Shadow private float yaw;
    @Shadow private float pitch;
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPos(Vec3d pos);

    private float capturedTickDelta = 0.0f;

    // ÉP MIXIN TÌM ĐÚNG BẢN BLOCKVIEW (1.21.10 TRỞ XUỐNG). KHÔNG CÓ THÌ BỎ QUA!
    @Inject(method = "update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V", at = @At("HEAD"), require = 0)
    private void captureTickDeltaOld(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean frontalView, float tickDelta, CallbackInfo ci) {
        this.capturedTickDelta = tickDelta;
    }

    // ÉP MIXIN TÌM ĐÚNG BẢN WORLD (1.21.11 TRỞ LÊN). KHÔNG CÓ THÌ BỎ QUA!
    @Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V", at = @At("HEAD"), require = 0)
    private void captureTickDeltaNew(World area, Entity focusedEntity, boolean thirdPerson, boolean frontalView, float tickDelta, CallbackInfo ci) {
        this.capturedTickDelta = tickDelta;
    }

    // HÀM CHÍNH: Vì không nhận tham số ngoài CallbackInfo, Mixin sẽ ăn thẳng vào bất kỳ bản nào.
    @Inject(method = "update", at = @At("TAIL"))
    private void onUpdateTail(CallbackInfo ci) {
        SmoothMotion module = SmoothMotion.INSTANCE;
        
        if (module != null && module.active) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) return;

            // Xài thẳng cái tickDelta thần thánh vừa bắt được
            float tickDelta = this.capturedTickDelta;
            
            boolean thirdPerson = !mc.options.getPerspective().isFirstPerson();

            if (!thirdPerson && !module.shouldSmoothPOV()) return;

            // Xoay chuẩn logic Perfect
            module.updateRot(this.yaw, this.pitch);
            this.setRotation(module.getRenderYaw(), module.getRenderPitch());

            // Vị trí chuẩn logic Perfect
            Vec3d smoothPos = module.updatePos(tickDelta, this.pos);
            this.setPos(smoothPos);
        }
    }
}