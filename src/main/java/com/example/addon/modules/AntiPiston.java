package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventRotate;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.MathHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class AntiPiston extends AddonModule {
    public static final AntiPiston INSTANCE = new AntiPiston();
    public boolean active = false;

    public final ModeOption<InteractionMode> placeMode   = new ModeOption<>(this, "Place Mode",    "Bypass mode for placing blocks.",        InteractionMode.Grim);
    public final SliderOption placeRange   = new SliderOption(this, "Place Range",   "Max range to place obsidian.",              4.5, 1.0, 6.0, 0.1);
    public final SliderOption crystalRange = new SliderOption(this, "Crystal Range", "Max range to attack crystals.",             4.5, 1.0, 6.0, 0.1);
    public final SliderOption attackDelay  = new SliderOption(this, "Attack Delay",  "Delay between crystal attacks (ms).",      50.0, 0.0, 500.0, 1.0);
    public final ToggleOption antiWeakness = new ToggleOption(this, "Anti Weakness", "Swap to sword when weakness effect.",      true);

    // Target đang cần rotate tới — set trong EventTick, đọc trong EventRotate
    private volatile Vec3d rotateTarget = null;
    private long lastAttackTime = 0;

    public AntiPiston() {
        super("AntiPiston", "Advanced counter for packet Piston Aura. Grim-compatible via Boze EventRotate pipeline.");
    }

    @Override public void onEnable()  { this.active = true; }
    @Override public void onDisable() { this.active = false; rotateTarget = null; }

    // ─────────────────────────────────────────────────────────────
    // TICK — xác định target, đặt obsidian, queue rotation
    // ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        rotateTarget = null; // reset mỗi tick

        // ── 1. Tìm crystal nguy hiểm, queue rotation + attack ──
        EndCrystalEntity targetCrystal = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            double dist = mc.player.distanceTo(crystal);
            if (dist > crystalRange.getValue()) continue;
            if (!isDangerousCrystal(mc, crystal)) continue;
            if (dist < bestDist) {
                bestDist = dist;
                targetCrystal = crystal;
            }
        }

        if (targetCrystal != null) {
            // Set rotate target — EventRotate sẽ xử lý đúng pipeline
            Vec3d aimPoint = MathHelper.getBestAimPoint(targetCrystal.getBoundingBox());
            rotateTarget = aimPoint;

            // Attack sau khi rotation đã được schedule (delay check)
            long now = System.currentTimeMillis();
            if (now - lastAttackTime >= attackDelay.getValue()) {
                final EndCrystalEntity finalCrystal = targetCrystal;
                attackCrystal(mc, finalCrystal);
                lastAttackTime = now;
            }
        }

        // ── 2. Block piston push target bằng obsidian ──
        BlockPos playerPos = mc.player.getBlockPos();
        int pRange = (int) Math.ceil(placeRange.getValue());

        for (int x = -pRange; x <= pRange; x++) {
            for (int y = -pRange; y <= pRange; y++) {
                for (int z = -pRange; z <= pRange; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);

                    if (state.getBlock() != Blocks.PISTON && state.getBlock() != Blocks.STICKY_PISTON) continue;

                    Direction facing = state.get(Properties.FACING);
                    BlockPos pushTarget = pos.offset(facing);

                    // Chỉ block vị trí đang bị đẩy vào sát player
                    if (!pushTarget.isWithinDistance(mc.player.getBlockPos(), 3.5)) continue;
                    if (!mc.world.getBlockState(pushTarget).isReplaceable()) continue;
                    if (!mc.world.getOtherEntities(null, new Box(pushTarget),
                            e -> !(e instanceof EndCrystalEntity)).isEmpty()) continue;

                    int obsSlot = InvHelper.find(Items.OBSIDIAN);
                    if (obsSlot == -1) continue;

                    placeBlockSilently(mc, pushTarget, obsSlot);
                    break; // 1 block per tick để không spam
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // EVENT ROTATE — đây là cách đúng để rotate trên Grim
    // Boze gọi event này ngay trước khi gửi movement packet
    // → rotation được bundle vào đúng packet, Grim không flag
    // ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onRotate(EventRotate event) {
        Vec3d target = rotateTarget;
        if (target == null) return;

        // isFree() = không có module nào khác đang rotate
        // → an toàn để set rotation
        if (!event.isFree()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // rotate() dùng MathHelper nội bộ của Boze để tính yaw/pitch
        // và set vào event.yaw / event.pitch
        // Boze sẽ đưa các giá trị này vào PlayerMoveC2SPacket đúng cách
        event.rotate(mc.player.getEyePos(), target);
    }

    // ─────────────────────────────────────────────────────────────
    // ATTACK CRYSTAL
    // ─────────────────────────────────────────────────────────────

    private void attackCrystal(MinecraftClient mc, EndCrystalEntity crystal) {
        boolean hasWeakness = mc.player.hasStatusEffect(StatusEffects.WEAKNESS);
        boolean needSword   = antiWeakness.getValue() && hasWeakness;
        int     swordSlot   = needSword ? findSwordInHotbar(mc) : -1;

        if (needSword && swordSlot != -1) {
            InvHelper.swapToSlot(swordSlot, SwapType.Silent);
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvHelper.swapBack();
        } else {
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PLACE OBSIDIAN — dùng PlaceHelper đúng API
    // ─────────────────────────────────────────────────────────────

    private void placeBlockSilently(MinecraftClient mc, BlockPos pos, int slot) {
        BlockHitResult hit = getValidHitResult(mc, pos);
        if (hit == null) return;

        InvHelper.swapToSlot(slot, SwapType.Silent);
        // PlaceHelper.place tự handle rotation cho Grim mode
        PlaceHelper.place(placeMode.getValue(), hit, Hand.MAIN_HAND);
        InvHelper.swapBack();
    }

    // ─────────────────────────────────────────────────────────────
    // HIT RESULT — tìm mặt block hợp lệ để click
    // ─────────────────────────────────────────────────────────────

    private BlockHitResult getValidHitResult(MinecraftClient mc, BlockPos targetPos) {
        Vec3d eyePos      = mc.player.getEyePos();
        BlockHitResult    fallback = null;

        for (Direction dir : Direction.values()) {
            BlockPos  neighbor     = targetPos.offset(dir);
            BlockState neighborState = mc.world.getBlockState(neighbor);

            // Cần neighbor là block solid (không replaceable, không fluid)
            if (neighborState.isReplaceable() || !neighborState.getFluidState().isEmpty()) continue;

            Direction clickFace = dir.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(neighbor).add(
                clickFace.getOffsetX() * 0.5,
                clickFace.getOffsetY() * 0.5,
                clickFace.getOffsetZ() * 0.5
            );

            BlockHitResult hit = new BlockHitResult(hitVec, clickFace, neighbor, false);

            // Ưu tiên mặt nhìn thấy thẳng (không bị block)
            var raycast = mc.world.raycast(new RaycastContext(
                eyePos, hitVec,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
            ));
            if (raycast.getType() == HitResult.Type.MISS) return hit;

            if (fallback == null) fallback = hit;
        }
        return fallback;
    }

    // ─────────────────────────────────────────────────────────────
    // DETECT CRYSTAL NGUY HIỂM
    // ─────────────────────────────────────────────────────────────

    private boolean isDangerousCrystal(MinecraftClient mc, EndCrystalEntity crystal) {
        // Đang đè lên player
        if (crystal.getBoundingBox().intersects(mc.player.getBoundingBox().expand(0.6))) return true;

        // Đang bị piston đẩy về phía player hoặc có redstone kích hoạt gần đó
        BlockPos cPos = crystal.getBlockPos();
        for (Direction dir : Direction.values()) {
            BlockPos   adj   = cPos.offset(dir);
            BlockState state = mc.world.getBlockState(adj);

            if ((state.getBlock() == Blocks.PISTON || state.getBlock() == Blocks.STICKY_PISTON)
                    && state.get(Properties.FACING) == dir.getOpposite()) return true;

            if (mc.world.isReceivingRedstonePower(adj)
                    || state.getBlock() == Blocks.REDSTONE_BLOCK) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private int findSwordInHotbar(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.NETHERITE_SWORD || item == Items.DIAMOND_SWORD
                    || item == Items.IRON_SWORD || item == Items.STONE_SWORD) return i;
        }
        return -1;
    }
}