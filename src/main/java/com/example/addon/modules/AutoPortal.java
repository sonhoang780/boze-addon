package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import dev.boze.api.utility.interaction.InteractionMode;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public class AutoPortal extends AddonModule {
    public static final AutoPortal INSTANCE = new AutoPortal();

    // ── KHAI BÁO 3 CHẾ ĐỘ SWAP ──
    public enum SwapMode {
        Normal, Alt, Silent
    }

    public final ModeOption<SwapMode> swapMode = new ModeOption<>(this, "Swap Mode", "Mode to swap items.", SwapMode.Alt);
    public final SliderOption delay = new SliderOption(this, "Delay", "Ticks between placing blocks.", 1.0, 0.0, 10.0, 1.0);

    private final List<BlockPos> frameBlocks = new ArrayList<>();
    private int buildIndex = 0;
    private int ticks = 0;
    private BlockPos basePos = null;

    public AutoPortal() {
        super("AutoPortal", "Automatically build and ignite a Nether Portal.");
    }

    @Override
    public void onEnable() {
        frameBlocks.clear();
        buildIndex = 0;
        ticks = 0;
        basePos = null;
    }

    private void info(String msg) {
        ChatHelper.sendMsg("AutoPortal", "§a" + msg);
    }

    // ── HÀM TÌM ĐỒ DỰA THEO MODE ──
    // Nếu là Alt: Cho phép tìm ở mọi ngóc ngách trong hòm.
    // Nếu là Normal/Silent: Bắt buộc phải tìm trong Hotbar.
    private int findItem(Item item) {
        if (swapMode.getValue() == SwapMode.Alt) {
            return InvHelper.find(item);
        } else {
            return InvHelper.findInHotbar(item);
        }
    }

    private SwapType getBozeSwapType() {
        switch (swapMode.getValue()) {
            case Normal: return SwapType.Normal;
            case Silent: return SwapType.Silent;
            case Alt: default: return SwapType.Alt;
        }
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // ── PHASE 1: KHỞI TẠO ──
        if (frameBlocks.isEmpty()) {
            if (mc.level.dimension() == Level.END) {
                info("Can't create nether portal at the end");
                this.setState(false); 
                return;
            }

            if (countItem(Items.OBSIDIAN) < 10) {
                info("Obsidians not found");
                this.setState(false);
                return;
            }

            // KIỂM TRA CHỐNG NGU TRƯỚC KHI XÂY: Xác nhận đồ có sẵn đúng chuẩn Mode chưa
            if (findItem(Items.OBSIDIAN) == -1) {
                info("Obsidians not found");
                this.setState(false);
                return;
            }
            if (findItem(Items.FLINT_AND_STEEL) == -1) {
                info("Flint and Steel not found");
                this.setState(false);
                return;
            }

            Direction forward = mc.player.getDirection();
            Direction right = forward.getClockWise();
            Direction left = right.getOpposite();

            basePos = mc.player.blockPosition().relative(forward, 2);

            // Cấu trúc khung cổng
            frameBlocks.add(basePos);
            frameBlocks.add(basePos.relative(right));
            frameBlocks.add(basePos.relative(left).above(1));
            frameBlocks.add(basePos.relative(left).above(2));
            frameBlocks.add(basePos.relative(left).above(3));
            frameBlocks.add(basePos.relative(right, 2).above(1));
            frameBlocks.add(basePos.relative(right, 2).above(2));
            frameBlocks.add(basePos.relative(right, 2).above(3));
            frameBlocks.add(basePos.above(4));
            frameBlocks.add(basePos.relative(right).above(4));
        }

        if (ticks < delay.getValue()) {
            ticks++;
            return;
        }
        ticks = 0;

        // ── PHASE 2: XÂY CỔNG ──
        if (buildIndex < frameBlocks.size()) {
            BlockPos target = frameBlocks.get(buildIndex);
            
            if (mc.level.getBlockState(target).canBeReplaced()) {
                int obbySlot = findItem(Items.OBSIDIAN);
                if (obbySlot == -1) {
                    info("Obsidians not found");
                    this.setState(false);
                    return;
                }
                
                // Tráo đồ bằng đúng Type mày đã chọn trong ClickGUI
                InvHelper.swapToSlot(obbySlot, getBozeSwapType());
                BlockHitResult hitResult = new BlockHitResult(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5), Direction.UP, target, false);
                PlaceHelper.place(InteractionMode.NCP, hitResult, InteractionHand.MAIN_HAND);
                InvHelper.swapBack();
            }
            buildIndex++;
            return;
        }

        // ── PHASE 3: BẬT LỬA ──
        int flintSlot = findItem(Items.FLINT_AND_STEEL); 
        if (flintSlot == -1) {
            info("Flint and Steel not found");
            this.setState(false);
            return;
        }

        BlockPos firePos = basePos.above(1);

        InvHelper.swapToSlot(flintSlot, getBozeSwapType());
        BlockHitResult hitResult = new BlockHitResult(new Vec3(firePos.getX() + 0.5, firePos.getY(), firePos.getZ() + 0.5), Direction.UP, firePos, false);
        PlaceHelper.place(InteractionMode.NCP, hitResult, InteractionHand.MAIN_HAND);
        InvHelper.swapBack();
        
        info("Portal Created!");
        this.setState(false);
    }

    private int countItem(Item item) {
        Minecraft mc = Minecraft.getInstance();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }
}