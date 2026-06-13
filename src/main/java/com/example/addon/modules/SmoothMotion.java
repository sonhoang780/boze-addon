package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.option.SliderOption;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import meteordevelopment.orbit.EventHandler;
import dev.boze.api.event.EventTick;

public class SmoothMotion extends AddonModule {
    public static final SmoothMotion INSTANCE = new SmoothMotion();

    public boolean active = false;

    // CÁC SETTING GỐC
    public final ToggleOption firstPerson = new ToggleOption(this, "First Person", "Làm mượt cả POV (Góc nhìn thứ nhất).", false);
    public final SliderOption transitionSmooth = new SliderOption(this, "Transition Smoothness", "Độ mượt khi chuyển F5.", 0.2, 0.0, 1.0, 0.001);
    public final SliderOption verticalSmooth = new SliderOption(this, "Vertical Smooth", "Độ mượt dọc.", 0.3, 0.0, 1.0, 0.001);
    public final SliderOption horizontalSmooth = new SliderOption(this, "Horizontal Smooth", "Độ mượt ngang.", 0.3, 0.0, 1.0, 0.001);
    public final SliderOption rotationSmooth = new SliderOption(this, "Rotation Smooth", "Độ mượt xoay.", 0.3, 0.0, 1.0, 0.001);

    // THÊM SETTING QUÁN TÍNH ĐẨY/KÉO CAM (LOGIC MỚI)
    public final ToggleOption dynamicDistance = new ToggleOption(this, "Dynamic Distance", "Hiệu ứng đẩy cam khi chạy, thu cam khi dừng.", true);
    public final SliderOption dynamicSpeed = new SliderOption(this, "Dynamic Speed", "Tốc độ bắt kịp của cam (Càng nhỏ cam càng đẩy ra xa).", 0.3, 0.0, 1.0, 0.001);

    private Vec3d smoothPos = null;
    private float renderYaw, renderPitch;
    private int lastPerspective = -1;
    private boolean isTransitioning = false;

    // Các biến lưu trữ tọa độ quán tính (Fake Position)
    private double fakeX, fakeY, fakeZ, prevFakeX, prevFakeY, prevFakeZ;
    private boolean initialized = false;

    private SmoothMotion() {
        super("SmoothMotion", "Camera Cinematic - Có hiệu ứng quán tính đẩy/kéo chuẩn Perfect.");
    }

    @Override
    public void onEnable() {
        active = true;
        smoothPos = null;
        initialized = false;
    }

    @Override
    public void onDisable() {
        active = false;
    }

    // TÍNH TOÁN QUÁN TÍNH LIÊN TỤC THEO TICK (LOGIC MỚI)
    @EventHandler
    private void onTick(EventTick.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            initialized = false;
            return;
        }
        
        if (!initialized) {
            fakeX = prevFakeX = mc.player.getX();
            fakeY = prevFakeY = mc.player.getY();
            fakeZ = prevFakeZ = mc.player.getZ();
            initialized = true;
            return;
        }

        prevFakeX = fakeX;
        prevFakeY = fakeY;
        prevFakeZ = fakeZ;

        double spd = dynamicSpeed.getValue();
        
        fakeX = animate(fakeX, mc.player.getX(), spd);
        fakeY = animate(fakeY, mc.player.getY(), spd);
        fakeZ = animate(fakeZ, mc.player.getZ(), spd);
    }

    private double animate(double current, double endPoint, double speed) {
        if (speed >= 1.0) return endPoint;
        if (speed == 0.0) return current;
        boolean shouldContinueAnimation = endPoint > current;
        double dif = Math.max(endPoint, current) - Math.min(endPoint, current);
        if (Math.abs(dif) <= 0.001) return endPoint;
        double factor = dif * speed;
        return current + (shouldContinueAnimation ? factor : -factor);
    }

    // UPDATE VỊ TRÍ + HÒA TRỘN QUÁN TÍNH
    public Vec3d updatePos(float tickDelta, Vec3d target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int currentPersp = mc.options.getPerspective().ordinal();

        if (smoothPos == null || smoothPos.distanceTo(target) > 300) {
            smoothPos = target;
            lastPerspective = currentPersp;
            isTransitioning = false;
            return target;
        }

        if (lastPerspective != currentPersp) {
            isTransitioning = true;
            lastPerspective = currentPersp;
        }

        Vec3d finalTarget = target;

        // CHỐT CHẶN: Chỉ tính khoảng đẩy kéo khi BẬT DYNAMIC DISTANCE và KHÔNG PHẢI GÓC NHÌN THỨ NHẤT
        if (dynamicDistance.getValue() && initialized && mc.player != null && !mc.options.getPerspective().isFirstPerson()) {
            double interpFakeX = MathHelper.lerp(tickDelta, prevFakeX, fakeX);
            double interpFakeY = MathHelper.lerp(tickDelta, prevFakeY, fakeY);
            double interpFakeZ = MathHelper.lerp(tickDelta, prevFakeZ, fakeZ);

            Vec3d actualPos = mc.player.getLerpedPos(tickDelta);

        // LOGIC MỚI: Cập nhật fakePos ngay tại khung hình (Render) thay vì Tick
        // Điều này đảm bảo fakePos luôn mượt theo FPS của máy bạn
            double offsetX = interpFakeX - actualPos.x;
            double offsetY = interpFakeY - actualPos.y;
            double offsetZ = interpFakeZ - actualPos.z;

            finalTarget = target.add(offsetX, offsetY, offsetZ);
        }

        double hF_val = isTransitioning ? transitionSmooth.getValue() : horizontalSmooth.getValue();
        double vF_val = isTransitioning ? transitionSmooth.getValue() : verticalSmooth.getValue();
        if (isTransitioning && smoothPos.distanceTo(finalTarget) < 0.1) {
            isTransitioning = false;
        }
        
        smoothPos = new Vec3d(
            MathHelper.lerp(hF_val, smoothPos.x, finalTarget.x),
            MathHelper.lerp(vF_val, smoothPos.y, finalTarget.y),
            MathHelper.lerp(hF_val, smoothPos.z, finalTarget.z)
        );

        return smoothPos;
    }

    public void updateRot(float targetYaw, float targetPitch) {
        if (smoothPos == null) {
            renderYaw = targetYaw;
            renderPitch = targetPitch;
            return;
        }
        float diffYaw = Math.abs(MathHelper.wrapDegrees(targetYaw - renderYaw));
        if (diffYaw > 120f) {
            renderYaw = targetYaw;
            renderPitch = targetPitch;
            return;
        }
        float rSpeed = rotationSmooth.getValue().floatValue();
        renderYaw = MathHelper.lerpAngleDegrees(rSpeed, renderYaw, targetYaw);
        renderPitch = MathHelper.lerp(rSpeed, renderPitch, targetPitch);
    }

    public float getRenderYaw() { return renderYaw; }
    public float getRenderPitch() { return renderPitch; }
    public boolean shouldSmoothPOV() { return firstPerson.getValue(); }
}