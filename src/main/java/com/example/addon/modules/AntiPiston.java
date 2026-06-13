package com.example.addon.modules;

import java.util.List;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import dev.boze.api.utility.interaction.InteractionMode;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AntiPiston extends AddonModule {
    public static final AntiPiston INSTANCE = new AntiPiston();
    public boolean active = false;

    // [CHUẨN API BOZE]: Khai báo Enum phục vụ cho ModeOption
    public enum SwitchMode {
        Silent("Silent"), Normal("Normal");
        
        private final String text;
        SwitchMode(String text) { this.text = text; }
        
        // Bắt buộc override toString để Boze GUI lấy tên hiển thị
        @Override 
        public String toString() { return text; } 
    }

    // ── OPTIONS ──
    public final SliderOption range = new SliderOption(this, "Range", "Tầm quét Piston xung quanh.", 5.0, 1.0, 10.0, 0.1);
    public final SliderOption actionDelay = new SliderOption(this, "Action Delay", "Delay (ticks) giữa các lần đặt/phá để tránh bị kick.", 1.0, 0.0, 20.0, 1.0);
    
    // [FIX LỖI COMPILER]: Khởi tạo ModeOption nhận Enum giống y như module PlayMusic
    public final ModeOption<SwitchMode> switchMode = new ModeOption<>(this, "Sword Switch", "Chế độ đổi kiếm đập Crystal.", SwitchMode.Silent);

    private int delayTimer = 0;

    private AntiPiston() {
        super("AntiPiston", "Tự động chặn Piston và ưu tiên phá Crystal (Anti-Weakness).");
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (this.active) onTick(client);
        });
    }

    @Override 
    public void onEnable() { 
        this.active = true; 
        this.delayTimer = 0;
    }
    
    @Override 
    public void onDisable() { 
        this.active = false; 
    }

    private void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        double r = range.getValue();
        BlockPos playerPos = mc.player.getBlockPos();
        int ir = (int) Math.ceil(r);

        for (int x = -ir; x <= ir; x++) {
            for (int y = -ir; y <= ir; y++) {
                for (int z = -ir; z <= ir; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);
                    
                    if (playerPos.getSquaredDistance(checkPos) > r * r) continue;

                    BlockState state = mc.world.getBlockState(checkPos);
                    
                    if (state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON)) {
                        Direction facing = state.get(Properties.FACING);
                        BlockPos targetPos = checkPos.offset(facing);

                        if (handlePistonTarget(mc, targetPos)) {
                            delayTimer = (int)(double) actionDelay.getValue();
                            return; 
                        }
                    }
                }
            }
        }
    }

    private boolean handlePistonTarget(MinecraftClient mc, BlockPos targetPos) {
        // [ƯU TIÊN SỐ 1]: Quét xem có Crystal trước mõm Piston không
        List<Entity> crystals = mc.world.getOtherEntities(null, new Box(targetPos), e -> e instanceof EndCrystalEntity);
        
        if (!crystals.isEmpty()) {
            Entity crystal = crystals.get(0);
            breakCrystal(mc, crystal);
            return true; 
        }

        // [ƯU TIÊN SỐ 2]: Nếu không có Crystal, và là chỗ trống -> Đặt Obsidian
        BlockState targetState = mc.world.getBlockState(targetPos);
        if (targetState.isReplaceable()) {
            int obbySlot = InvHelper.findInHotbar(Items.OBSIDIAN);
            if (obbySlot != -1) {
                placeBlockSilently(mc, targetPos, obbySlot);
                return true; 
            }
        }
        
        return false;
    }

    private void breakCrystal(MinecraftClient mc, Entity crystal) {
        boolean hasWeakness = mc.player.hasStatusEffect(StatusEffects.WEAKNESS);
        int swordSlot = findSwordInHotbar(mc);

        if (hasWeakness && swordSlot != -1) {
            // [FIX LỖI COMPILER]: Kiểm tra trực tiếp Enum thay vì String
            if (switchMode.getValue() == SwitchMode.Silent) {
                InvHelper.swapToSlot(swordSlot, SwapType.Silent);
                mc.interactionManager.attackEntity(mc.player, crystal);
                mc.player.swingHand(Hand.MAIN_HAND);
                InvHelper.swapBack();
            } else {
                InvHelper.swapToSlot(swordSlot, SwapType.Normal);
                mc.interactionManager.attackEntity(mc.player, crystal);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        } else {
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private int findSwordInHotbar(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.NETHERITE_SWORD || item == Items.DIAMOND_SWORD || 
                item == Items.IRON_SWORD || item == Items.STONE_SWORD || 
                item == Items.WOODEN_SWORD || item == Items.GOLDEN_SWORD) {
                return i;
            }
        }
        return -1; 
    }

    private void placeBlockSilently(MinecraftClient mc, BlockPos pos, int slot) {
        InvHelper.swapToSlot(slot, SwapType.Silent);

        BlockHitResult hitResult = new BlockHitResult(
            new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), 
            Direction.UP, 
            pos, 
            false
        );

        PlaceHelper.place(InteractionMode.NCP, hitResult, Hand.MAIN_HAND);
        
        InvHelper.swapBack();
    }
}