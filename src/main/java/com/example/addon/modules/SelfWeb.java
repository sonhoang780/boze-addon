package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import dev.boze.api.utility.interaction.InteractionMode;

public class SelfWeb extends AddonModule {
    public static final SelfWeb INSTANCE = new SelfWeb();

    public final ToggleOption autoDisable = new ToggleOption(this, "AutoDisable", "Automatically disable after placing.", true);

    private SelfWeb() {
        super("SelfWeb", "Automatically places a cobweb at the player's head position.");
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        BlockPos headPos = mc.player.blockPosition().above();

        if (!mc.level.getBlockState(headPos).canBeReplaced()) {
            if (autoDisable.getValue()) this.setState(false); // ✅ Tắt module chuẩn
            return;
        }

        // TÍNH NĂNG MỚI: Chỉ tìm trong Hotbar (Slot 0-8)
        int webSlot = InvHelper.findInHotbar(Items.COBWEB);
        
        // NẾU KHÔNG CÓ TRONG HOTBAR -> Báo lỗi & Tắt module
        if (webSlot == -1) {
            mc.player.sendSystemMessage(Component.literal("Web not found"));
            this.setState(false); 
            return;
        }

        InvHelper.swapToSlot(webSlot, SwapType.Silent);

        BlockHitResult hitResult = new BlockHitResult(
            new Vec3(headPos.getX() + 0.5, headPos.getY(), headPos.getZ() + 0.5),
            Direction.DOWN,
            headPos,
            false
        );

        PlaceHelper.place(InteractionMode.NCP, hitResult, InteractionHand.MAIN_HAND);
        InvHelper.swapBack();

        if (autoDisable.getValue()) {
            this.setState(false); // ✅ Tắt module chuẩn
        }
    }
}