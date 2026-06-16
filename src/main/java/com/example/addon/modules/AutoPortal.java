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
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

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
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // ── PHASE 1: KHỞI TẠO ──
        if (frameBlocks.isEmpty()) {
            if (mc.world.getRegistryKey() == World.END) {
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

            Direction forward = mc.player.getHorizontalFacing();
            Direction right = forward.rotateYClockwise();
            Direction left = right.getOpposite();

            basePos = mc.player.getBlockPos().offset(forward, 2);

            // Cấu trúc khung cổng
            frameBlocks.add(basePos);
            frameBlocks.add(basePos.offset(right));
            frameBlocks.add(basePos.offset(left).up(1));
            frameBlocks.add(basePos.offset(left).up(2));
            frameBlocks.add(basePos.offset(left).up(3));
            frameBlocks.add(basePos.offset(right, 2).up(1));
            frameBlocks.add(basePos.offset(right, 2).up(2));
            frameBlocks.add(basePos.offset(right, 2).up(3));
            frameBlocks.add(basePos.up(4));
            frameBlocks.add(basePos.offset(right).up(4));
        }

        if (ticks < delay.getValue()) {
            ticks++;
            return;
        }
        ticks = 0;

        // ── PHASE 2: XÂY CỔNG ──
        if (buildIndex < frameBlocks.size()) {
            BlockPos target = frameBlocks.get(buildIndex);
            
            if (mc.world.getBlockState(target).isReplaceable()) {
                int obbySlot = findItem(Items.OBSIDIAN);
                if (obbySlot == -1) {
                    info("Obsidians not found");
                    this.setState(false);
                    return;
                }
                
                // Tráo đồ bằng đúng Type mày đã chọn trong ClickGUI
                InvHelper.swapToSlot(obbySlot, getBozeSwapType());
                BlockHitResult hitResult = new BlockHitResult(new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5), Direction.UP, target, false);
                PlaceHelper.place(InteractionMode.NCP, hitResult, Hand.MAIN_HAND);
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

        BlockPos firePos = basePos.up(1);

        InvHelper.swapToSlot(flintSlot, getBozeSwapType());
        BlockHitResult hitResult = new BlockHitResult(new Vec3d(firePos.getX() + 0.5, firePos.getY(), firePos.getZ() + 0.5), Direction.UP, firePos, false);
        PlaceHelper.place(InteractionMode.NCP, hitResult, Hand.MAIN_HAND);
        InvHelper.swapBack();
        
        info("Portal Created!");
        this.setState(false);
    }

    private int countItem(Item item) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }
}