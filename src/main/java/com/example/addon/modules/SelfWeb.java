package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.text.Text; // Import thêm Text để in ra chat
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import dev.boze.api.utility.interaction.InteractionMode;

public class SelfWeb extends AddonModule {
    public static final SelfWeb INSTANCE = new SelfWeb();

    public final ToggleOption autoDisable = new ToggleOption(this, "Auto Disable", "Tự tắt sau khi đặt.", true);

    private SelfWeb() {
        super("SelfWeb", "Tự động đặt mạng nhện ở mặt (Doc-Compliant API).");
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        BlockPos headPos = mc.player.getBlockPos().up();

        if (!mc.world.getBlockState(headPos).isReplaceable()) {
            if (autoDisable.getValue()) this.setState(false); // ✅ Tắt module chuẩn
            return;
        }

        // TÍNH NĂNG MỚI: Chỉ tìm trong Hotbar (Slot 0-8)
        int webSlot = InvHelper.findInHotbar(Items.COBWEB);
        
        // NẾU KHÔNG CÓ TRONG HOTBAR -> Báo lỗi & Tắt module
        if (webSlot == -1) {
            mc.player.sendMessage(Text.literal("Web not found"), false);
            this.setState(false); 
            return;
        }

        InvHelper.swapToSlot(webSlot, SwapType.Silent);

        BlockHitResult hitResult = new BlockHitResult(
            new Vec3d(headPos.getX() + 0.5, headPos.getY(), headPos.getZ() + 0.5),
            Direction.DOWN,
            headPos,
            false
        );

        PlaceHelper.place(InteractionMode.NCP, hitResult, Hand.MAIN_HAND);
        InvHelper.swapBack();

        if (autoDisable.getValue()) {
            this.setState(false); // ✅ Tắt module chuẩn
        }
    }
}