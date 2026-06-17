package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.MathHelper; // Đã thêm
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
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket; // Đã thêm
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;

public class AntiPiston2 extends AddonModule {
    public static final AntiPiston2 INSTANCE = new AntiPiston2();
    public boolean active = false;

    public enum SwitchMode {
        Silent("Silent"), Normal("Normal");
        private final String text;
        SwitchMode(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }

    public final ModeOption<SwitchMode>  switchMode   = new ModeOption<>(this, "AntiWeakness Switch",  "",                          SwitchMode.Silent);
    public final ModeOption<InteractionMode> placeMode = new ModeOption<>(this, "Place Mode",   "",                InteractionMode.Grim);
    public final SliderOption range       = new SliderOption(this, "Range",        "",                          5.0, 1.0, 8.0,   0.1);
    public final SliderOption crystalRange = new SliderOption(this, "Crystal Range","",                          5.0, 1.0, 8.0,   0.1);
    public final ToggleOption antiWeakness = new ToggleOption(this, "Anti Weakness","",             true);
    public final ToggleOption packetMode   = new ToggleOption(this, "Packet Mode",  "Packet break", true);

    private AntiPiston2() {
        super("AntiPiston2", "Anti Piston Crystal");
    }

    @Override public void onEnable()  { this.active = true; }
    @Override public void onDisable() { this.active = false; }

    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (!packetMode.getValue()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        var packet = event.getPacket();

        if (packet instanceof BlockEventS2CPacket pkt) {
            if (pkt.getType() != 0) return;

            BlockPos pistonPos = pkt.getPos();
            BlockState state   = mc.world.getBlockState(pistonPos);
            if (!state.isOf(Blocks.PISTON) && !state.isOf(Blocks.STICKY_PISTON)) return;

            Direction facing = Direction.values()[pkt.getData() % 6];
            BlockPos  target = pistonPos.offset(facing);

            mc.execute(() -> reactToPistonTarget(mc, target));
        }

        if (packet instanceof BlockUpdateS2CPacket pkt) {
            BlockPos pos   = pkt.getPos();
            BlockState newState = pkt.getState();

            // Chặn ngay lập tức khi thằng địch VỪA ĐẶT Piston xuống đất
            if (newState.isOf(Blocks.PISTON) || newState.isOf(Blocks.STICKY_PISTON)) {
                Direction facing = newState.get(Properties.FACING);
                BlockPos target  = pos.offset(facing);
                mc.execute(() -> reactToPistonTarget(mc, target));
            }

            if (!mc.world.getOtherEntities(null, new Box(pos), e -> e instanceof EndCrystalEntity).isEmpty()) {
                mc.execute(() -> breakNearbyCrystals(mc));
            }
        }

        if (packet instanceof ChunkDeltaUpdateS2CPacket pkt) {
            pkt.visitUpdates((pos, state) -> {
                if (state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON)) {
                    if (state.contains(Properties.EXTENDED) && state.get(Properties.EXTENDED)) {
                        Direction facing = state.get(Properties.FACING);
                        BlockPos target  = pos.offset(facing);
                        mc.execute(() -> reactToPistonTarget(mc, target));
                    }
                }
            });
        }
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        breakNearbyCrystals(mc);

        if (packetMode.getValue()) return; 

        BlockPos playerPos = mc.player.getBlockPos();
        int ir = (int) Math.ceil(range.getValue());

        outer:
        for (int x = -ir; x <= ir; x++) {
            for (int y = -ir; y <= ir; y++) {
                for (int z = -ir; z <= ir; z++) {
                    BlockPos checkPos = playerPos.add(x, y, z);
                    if (playerPos.getSquaredDistance(checkPos) > range.getValue() * range.getValue()) continue;

                    BlockState state = mc.world.getBlockState(checkPos);
                    if (!state.isOf(Blocks.PISTON) && !state.isOf(Blocks.STICKY_PISTON)) continue;

                    Direction facing = state.get(Properties.FACING);
                    BlockPos target  = checkPos.offset(facing);
                    if (reactToPistonTarget(mc, target)) break outer;
                }
            }
        }
    }

    private boolean reactToPistonTarget(MinecraftClient mc, BlockPos target) {
        if (mc.player == null || mc.world == null) return false;

        if (!target.isWithinDistance(mc.player.getBlockPos(), range.getValue() + 1)) return false;

        List<Entity> crystals = mc.world.getOtherEntities(
            null, new Box(target).expand(0.5),
            e -> e instanceof EndCrystalEntity);
        if (!crystals.isEmpty()) {
            breakCrystal(mc, (EndCrystalEntity) crystals.get(0));
            return true;
        }

        if (mc.world.getBlockState(target).isReplaceable()) {
            int slot = InvHelper.findInHotbar(Items.OBSIDIAN);
            if (slot != -1) {
                placeBlock(mc, target, slot);
                return true;
            }
        }

        return false;
    }

    private void breakNearbyCrystals(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        double cr = crystalRange.getValue();

        EndCrystalEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity crystal)) continue;
            double dist = mc.player.distanceTo(crystal);
            if (dist > cr) continue;
            if (crystal.getBoundingBox().intersects(mc.player.getBoundingBox().expand(1.0))) {
                breakCrystal(mc, crystal);
                return;
            }
            if (dist < bestDist) { bestDist = dist; best = crystal; }
        }
        if (best != null) breakCrystal(mc, best);
    }

    private void breakCrystal(MinecraftClient mc, EndCrystalEntity crystal) {
        // [FIX F5 + GRIM BYPASS]: Xử lý quay mặt siêu tốc
        Vec3d aimPoint = MathHelper.getBestAimPoint(crystal.getBoundingBox());
        float[] rot = MathHelper.calculateRotation(mc.player.getEyePos(), aimPoint);
        float yaw = rot[0];
        float pitch = rot[1];

        // 1. Ném Packet quay mặt lên Server cho Grim nó duyệt
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), false));
        
        // 2. Ép Player Model ở Client vẩy đầu theo góc quay (Hiển thị mượt trong F5)
        mc.player.setHeadYaw(yaw);
        mc.player.setBodyYaw(yaw);

        // 3. Tiến hành chém
        boolean hasWeakness = antiWeakness.getValue()
            && mc.player.hasStatusEffect(StatusEffects.WEAKNESS);
        int swordSlot = hasWeakness ? findSwordInHotbar(mc) : -1;

        if (hasWeakness && swordSlot != -1) {
            SwapType st = switchMode.getValue() == SwitchMode.Silent
                ? SwapType.Silent : SwapType.Normal;
            InvHelper.swapToSlot(swordSlot, st);
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
            if (st == SwapType.Silent) InvHelper.swapBack();
        } else {
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        
        // 4. Trả góc nhìn thật lại cho Server (Tránh lỗi đi lùi của Grim)
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), false));
    }

    private void placeBlock(MinecraftClient mc, BlockPos pos, int slot) {
        BlockHitResult hit = findHitResult(mc, pos);
        if (hit == null) return;

        InvHelper.swapToSlot(slot, SwapType.Silent);
        PlaceHelper.place(placeMode.getValue(), hit, Hand.MAIN_HAND);
        InvHelper.swapBack();
    }

    private BlockHitResult findHitResult(MinecraftClient mc, BlockPos pos) {
        Vec3d eyes    = mc.player.getEyePos();
        BlockHitResult fallback = null;

        for (Direction dir : Direction.values()) {
            BlockPos   neighbor = pos.offset(dir);
            BlockState nState   = mc.world.getBlockState(neighbor);
            if (nState.isReplaceable() || !nState.getFluidState().isEmpty()) continue;

            Direction clickFace = dir.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(neighbor).add(
                clickFace.getOffsetX() * 0.5,
                clickFace.getOffsetY() * 0.5,
                clickFace.getOffsetZ() * 0.5);

            BlockHitResult hit = new BlockHitResult(hitVec, clickFace, neighbor, false);

            var ray = mc.world.raycast(new RaycastContext(
                eyes, hitVec,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player));
            if (ray.getType() == HitResult.Type.MISS) return hit;
            if (fallback == null) fallback = hit;
        }
        return fallback;
    }

    private int findSwordInHotbar(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            Item it = mc.player.getInventory().getStack(i).getItem();
            if (it == Items.NETHERITE_SWORD || it == Items.DIAMOND_SWORD
                    || it == Items.IRON_SWORD || it == Items.STONE_SWORD
                    || it == Items.WOODEN_SWORD || it == Items.GOLDEN_SWORD)
                return i;
        }
        return -1;
    }
}