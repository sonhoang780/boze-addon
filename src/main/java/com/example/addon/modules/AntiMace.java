package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Xây cột obsidian hình nấm (stem 4 block + mũ 4×4) để đỡ đòn Mace dive.
 *
 * Cấu trúc (nhìn từ bên hông):
 *   ████  ← mũ nấm 4×4 tại Y+4
 *    █    ← thân 1×1, 4 block (Y đến Y+3)
 *  [Người chơi]
 */
public class AntiMace extends AddonModule {
    public static final AntiMace INSTANCE = new AntiMace();

    public enum SwapMode { Normal, Alt, Silent }

    public final ModeOption<SwapMode> swapMode = new ModeOption<>(this, "Swap Mode",
        "", SwapMode.Alt);
    public final SliderOption delay = new SliderOption(this, "Delay",
        "Tick delay each place", 1.0, 0.0, 10.0, 1.0);
    public final ToggleOption autoTrigger = new ToggleOption(this, "Auto Trigger",
        "Auto trigger when enemy with Mace is detected", true);

    // OBSIDIAN_NEEDED = 4 (thân) + 16 (mũ 4×4) = 20
    private static final int OBSIDIAN_NEEDED = 20;

    private final List<BlockPos> buildQueue = new ArrayList<>();
    private int buildIndex = 0;
    private int tickCount = 0;
    private boolean building = false;

    public AntiMace() {
        super("AntiMace", "Build obsidian mushroom to block Mace dive.");
    }

    @Override
    public void onEnable() {
        reset();
        if (!autoTrigger.getValue()) {
            initBuild(MinecraftClient.getInstance());
        }
    }

    @Override
    public void onDisable() {
        reset();
    }

    private void reset() {
        buildQueue.clear();
        buildIndex = 0;
        tickCount = 0;
        building = false;
    }

    /**
     * Khởi tạo hàng đợi xây tại vị trí hiện tại của người chơi.
     * Thân: base → base+3 (4 block cao, 1×1).
     * Mũ: base+4, offset dx/dz từ -1 đến +2 (4×4, tâm ở vị trí người chơi).
     */
    private void initBuild(MinecraftClient mc) {
        if (building || mc.player == null) return;

        int have = countItem(mc, Items.OBSIDIAN);
        if (have < OBSIDIAN_NEEDED) {
            ChatHelper.sendMsg("AntiMace", "§cNeed " + OBSIDIAN_NEEDED + " obsidian (have " + have + ")");
            return;
        }

        buildQueue.clear();
        buildIndex = 0;

        BlockPos base = mc.player.getBlockPos();

        // Thân nấm: 4 block dọc tại vị trí người chơi
        for (int i = 0; i < 4; i++) {
            buildQueue.add(base.up(i));
        }

        // Mũ nấm: 4×4 tại Y+4, căn giữa theo người chơi
        int capY = base.getY() + 4;
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                buildQueue.add(new BlockPos(base.getX() + dx, capY, base.getZ() + dz));
            }
        }

        building = true;
        ChatHelper.sendMsg("AntiMace", "§aBuilding obsidian mushroom (" + buildQueue.size() + " blocks)...");
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // Auto trigger: phát hiện kẻ địch cầm mace đang bay bên trên và gần
        if (autoTrigger.getValue() && !building && buildQueue.isEmpty()) {
            boolean threat = mc.world.getPlayers().stream()
                .anyMatch(p -> p != mc.player
                    && !p.isOnGround()
                    && p.getY() > mc.player.getY() + 3
                    && horizontalDist(mc.player.getX(), mc.player.getZ(), p.getX(), p.getZ()) < 24.0
                    && (p.getMainHandStack().isOf(Items.MACE) || p.getOffHandStack().isOf(Items.MACE)));
            if (threat) initBuild(mc);
        }

        if (!building) return;

        if (buildIndex >= buildQueue.size()) {
            ChatHelper.sendMsg("AntiMace", "§aCompleted!");
            building = false;
            return;
        }

        if (tickCount < delay.getValue()) {
            tickCount++;
            return;
        }
        tickCount = 0;

        BlockPos target = buildQueue.get(buildIndex++);

        if (mc.world.getBlockState(target).isReplaceable()) {
            int slot = findItem(mc, Items.OBSIDIAN);
            if (slot == -1) {
                ChatHelper.sendMsg("AntiMace", "§cHết obsidian!");
                building = false;
                return;
            }
            InvHelper.swapToSlot(slot, getSwapType());
            BlockHitResult hit = new BlockHitResult(
                new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
                Direction.UP, target, false
            );
            PlaceHelper.place(InteractionMode.NCP, hit, Hand.MAIN_HAND);
            InvHelper.swapBack();
        }
    }

    private double horizontalDist(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private int findItem(MinecraftClient mc, Item item) {
        return swapMode.getValue() == SwapMode.Alt ? InvHelper.find(item) : InvHelper.findInHotbar(item);
    }

    private SwapType getSwapType() {
        return switch (swapMode.getValue()) {
            case Normal -> SwapType.Normal;
            case Silent -> SwapType.Silent;
            default -> SwapType.Alt;
        };
    }

    private int countItem(MinecraftClient mc, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }
}
