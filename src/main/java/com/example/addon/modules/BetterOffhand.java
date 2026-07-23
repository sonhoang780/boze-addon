package com.example.addon.modules;

import com.example.addon.modules.bedaura.DamageUtils;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Offhand management: keeps a totem in the offhand, and swaps in an apple while
 * sword-fighting above a health threshold.
 *
 * Legit off (default): the offhand slot itself is kept directly topped up with a totem
 * (one container SWAP click into offhand button 40 per tick when empty) -- simple, but
 * every refill is a click packet.
 *
 * Legit on: a backup totem is parked in a chosen hotbar Slot instead. The instant the
 * offhand totem pops (ClientboundEntityEventPacket id 35), that slot is swapped into the
 * offhand via the vanilla F key (SWAP_ITEM_WITH_OFFHAND -- InvMovePlus.offhandSwapFromHotbar),
 * the exact packet a real player sends pressing F. Zero container clicks, zero added delay.
 * If the slot has no totem (Replenish's job to keep it stocked -- see Replenish.java, which
 * restocks any hotbar slot it remembers holding a totem regardless of MainHand's own slot),
 * falls back to a direct container SWAP click pulling a totem from the main inventory
 * straight into the offhand (button 40 is a legal SWAP target for the offhand slot).
 */
public class BetterOffhand extends AddonModule {
    public static final BetterOffhand INSTANCE = new BetterOffhand();

    private static final int OFFHAND_SWAP_BUTTON = 40;

    public final SliderOption health = new SliderOption(this, "Health",
            "Sword gap triggers only above this combined health+absorption "
            + "(36 = 20 max health + 16 max absorption from an enchanted golden apple).",
            20.0, 0.0, 36.0, 1.0);

    public final ToggleOption swordGap = new ToggleOption(this, "SwordGap",
            "Holding a sword and right-clicking above the Health threshold swaps the offhand "
            + "to an apple (prefers enchanted golden apple, falls back to a regular one).", false);

    public final ToggleOption legit = new ToggleOption(this, "Legit",
            "Park the offhand totem's backup in a hotbar Slot and swap it in via the vanilla "
            + "F key the instant the offhand totem pops, instead of always holding it directly "
            + "in the offhand slot.", false);

    public final SliderOption slot = new SliderOption(this, "Slot",
            "Hotbar slot that holds the backup totem for Legit mode.",
            8.0, 0.0, 8.0, 1.0, (java.util.function.BooleanSupplier) legit::getValue);

    // ── Legit predictive triggers, ported from ThunderHack-Reborn's AutoTotem ─────────────
    public final ToggleOption onFall = new ToggleOption(this, "OnFall",
            "Arm early if fall damage would be lethal.", true, (java.util.function.BooleanSupplier) legit::getValue);
    public final ToggleOption onElytra = new ToggleOption(this, "OnElytra",
            "Arm early while gliding on an elytra.", true, (java.util.function.BooleanSupplier) legit::getValue);
    public final ToggleOption onCreeper = new ToggleOption(this, "OnCreeper",
            "Arm early when any creeper is within 6 blocks.", true, (java.util.function.BooleanSupplier) legit::getValue);
    public final ToggleOption onTnt = new ToggleOption(this, "OnTNT",
            "Arm early when primed TNT is within 6 blocks.", true, (java.util.function.BooleanSupplier) legit::getValue);
    public final ToggleOption onMinecartTnt = new ToggleOption(this, "OnMinecartTNT",
            "Arm early when a TNT minecart is within 6 blocks.", true, (java.util.function.BooleanSupplier) legit::getValue);
    public final ToggleOption onAnchor = new ToggleOption(this, "OnAnchor",
            "Arm early near a respawn anchor (overworld/nether explosion risk).", true, (java.util.function.BooleanSupplier) legit::getValue);
    public final ToggleOption onCrystalInHand = new ToggleOption(this, "OnCrystalInHand",
            "Arm early when a nearby enemy is holding obsidian or an end crystal.", false, (java.util.function.BooleanSupplier) legit::getValue);

    private int nextActionTick;

    public BetterOffhand() {
        super("BetterOffhand", "Keeps a totem in the offhand and swaps in an apple while sword-fighting healthy.");
    }

    private void click(Minecraft mc, int slotId, int button, ContainerInput type) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotId, button, type, mc.player);
    }

    // InventoryMenu slot id for an inventory index (hotbar 0-8 -> 36+i, main 9-35 -> i).
    private int menuSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) return 36 + invIndex;
        return invIndex;
    }

    // First inventory index in [start,end] holding item; -1 if none.
    private int findItem(Minecraft mc, Item item, int start, int end) {
        Inventory inv = mc.player.getInventory();
        for (int i = start; i <= end; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }

    // Same as findItem, but skips one index -- used to search the WHOLE inventory (hotbar
    // included) for a totem while still not raiding MainHand's own dedicated slot.
    private int findItem(Minecraft mc, Item item, int start, int end, int skip) {
        Inventory inv = mc.player.getInventory();
        for (int i = start; i <= end; i++) {
            if (i == skip) continue;
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }

    private int mainHandSlot() {
        return MainHand.INSTANCE.getState() ? MainHand.INSTANCE.slot.getValue().intValue() : -1;
    }

    // Totem search for Legit's backup-slot logic: whole inventory (hotbar included), skipping
    // MainHand's own slot and (when filling the backup slot itself) the backup slot too, so it
    // never tries to swap a slot into itself. A totem parked ONLY in some other hotbar slot
    // (not main storage, not the backup Slot) was previously invisible to this search.
    private int findTotemForBackup(Minecraft mc, int excludeSlot) {
        int mh = mainHandSlot();
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i <= 35; i++) {
            if (i == mh || i == excludeSlot) continue;
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == Items.TOTEM_OF_UNDYING) return i;
        }
        return -1;
    }

    private boolean isTotem(ItemStack s) {
        return !s.isEmpty() && s.getItem() == Items.TOTEM_OF_UNDYING;
    }

    private boolean isApple(ItemStack s) {
        return !s.isEmpty() && (s.getItem() == Items.GOLDEN_APPLE || s.getItem() == Items.ENCHANTED_GOLDEN_APPLE);
    }

    /**
     * Deadly-crystal check, ported from lambda-client's AutoTotem Reason.EndCrystal: reuses
     * PistonCrystal's own damage formula (DamageUtils.estimateHpLoss, real armor, real
     * terrain occlusion) to see if a nearby live EndCrystal detonating right now would
     * out-damage current health+absorption.
     */
    private boolean hasDeadlyCrystal(Minecraft mc) {
        double combined = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EndCrystal c) || !c.isAlive()) continue;
            if (c.distanceToSqr(mc.player) > 144.0) continue; // 12 blocks, 2x blast radius
            if (crystalDamage(c.position(), mc.player, combined)) return true;
        }
        return false;
    }

    private boolean crystalDamage(net.minecraft.world.phys.Vec3 pos, net.minecraft.world.entity.player.Player target, double combined) {
        float dmg = DamageUtils.estimateHpLoss(pos, target, 0, DamageUtils.CRYSTAL_EXPLOSION_RADIUS, false, false);
        return dmg >= combined;
    }

    /**
     * Every other predictive trigger, ported from ThunderHack-Reborn's AutoTotem.getItemSlot
     * entity/block scan loop (onFall/onElytra/onCreeper/onTnt/onMinecartTnt/onAnchor/
     * onCrystalInHand) -- arms Legit's offhand BEFORE the actual damage event instead of
     * reacting to the totem pop, so a chained combo (crystal aura, TNT, etc) doesn't land its
     * second hit before a purely reactive swap's click round-trips to the server.
     */
    private boolean shouldArmEarly(Minecraft mc) {
        double combined = mc.player.getHealth() + mc.player.getAbsorptionAmount();

        if (onFall.getValue()) {
            float fallEstimate = (float) ((mc.player.fallDistance - 3) / 2.0 + 3.5);
            if (mc.player.fallDistance > 3 && combined - fallEstimate < 0.5) return true;
        }
        if (onElytra.getValue() && mc.player.isFallFlying()) return true;
        if (hasDeadlyCrystal(mc)) return true;

        if (onCreeper.getValue() || onTnt.getValue() || onMinecartTnt.getValue() || onCrystalInHand.getValue()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.distanceToSqr(mc.player) > 36.0) continue; // 6 blocks
                if (onCreeper.getValue() && e instanceof Creeper) return true;
                if (onTnt.getValue() && e instanceof PrimedTnt) return true;
                if (onMinecartTnt.getValue() && e instanceof MinecartTNT) return true;
                if (onCrystalInHand.getValue() && e instanceof Player p && p != mc.player
                        && (isDangerHeld(p.getMainHandItem()) || isDangerHeld(p.getOffhandItem()))) return true;
            }
        }

        if (onAnchor.getValue()) {
            BlockPos center = mc.player.blockPosition();
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -4, -4), center.offset(4, 4, 4))) {
                if (mc.level.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR)) return true;
            }
        }

        return false;
    }

    private boolean isDangerHeld(ItemStack s) {
        return !s.isEmpty() && (s.getItem() == Items.OBSIDIAN || s.getItem() == Items.END_CRYSTAL);
    }

    /** Ping-scaled cooldown between restock actions -- mirrors ThunderHack's delay = 2 + ping/25. */
    private boolean onCooldown(Minecraft mc) {
        if (mc.player.tickCount < nextActionTick) return true;
        int ping = 0;
        PlayerInfo info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(mc.player.getUUID()) : null;
        if (info != null) ping = info.getLatency();
        nextActionTick = mc.player.tickCount + (int) (2 + ping / 25.0);
        return false;
    }

    /** Legit's shared arm action: F-swap the backup slot in, or fall back to a container SWAP. */
    private void armOffhand(Minecraft mc, int backupSlot) {
        if (isTotem(mc.player.getOffhandItem()) || onCooldown(mc)) return;
        if (isTotem(mc.player.getInventory().getItem(backupSlot)) && InvMovePlus.offhandSwapFromHotbar(mc, backupSlot)) return;
        int src = findTotemForBackup(mc, backupSlot);
        if (src >= 0) click(mc, menuSlot(src), OFFHAND_SWAP_BUTTON, ContainerInput.SWAP);
    }

    // ── Legit off: keep the offhand slot itself topped up with a totem ────
    // ── Legit on: pre-arm early if a nearby crystal would currently be lethal ─

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.containerMenu.containerId != 0) return; // own inventory only

        if (!legit.getValue()) {
            if (!isTotem(mc.player.getOffhandItem())) {
                int src = findItem(mc, Items.TOTEM_OF_UNDYING, 0, 35, mainHandSlot());
                if (src >= 0) click(mc, menuSlot(src), OFFHAND_SWAP_BUTTON, ContainerInput.SWAP);
            }
        } else {
            // Own restock: Replenish only refills a hotbar slot it has already SEEN holding
            // an item (remembered[]) -- a Slot picked here that never had a totem in it
            // manually stays null in Replenish's memory forever, so it never gets refilled.
            // BetterOffhand has to keep its own designated backup slot stocked directly.
            int backupSlot = slot.getValue().intValue();
            if (!isTotem(mc.player.getInventory().getItem(backupSlot))) {
                int src = findTotemForBackup(mc, backupSlot);
                // SWAP's button param is an Inventory index (0-8 hotbar / 40 offhand sentinel),
                // NOT a menu-slot id -- menuSlot(backupSlot) here silently no-ops server-side
                // since 36-44 isn't a valid SWAP target index. Root cause of totem never
                // landing in the backup slot; unrelated to the earlier 9-35 search-range fix.
                if (src >= 0) click(mc, menuSlot(src), backupSlot, ContainerInput.SWAP);
            } else if (shouldArmEarly(mc)) {
                armOffhand(mc, backupSlot);
            }
        }

        handleSwordGap(mc);
    }

    private void handleSwordGap(Minecraft mc) {
        if (!swordGap.getValue()) return;
        if (!mc.options.keyUse.isDown()) return;
        if (!mc.player.getMainHandItem().is(ItemTags.SWORDS)) return;
        double combined = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (combined <= health.getValue()) return;
        if (isApple(mc.player.getOffhandItem())) return; // already set

        int idx = findItem(mc, Items.ENCHANTED_GOLDEN_APPLE, 0, 35);
        if (idx < 0) idx = findItem(mc, Items.GOLDEN_APPLE, 0, 35);
        if (idx < 0) return;

        if (idx > 8 || !InvMovePlus.offhandSwapFromHotbar(mc, idx)) {
            int ms = menuSlot(idx);
            click(mc, ms, 0, ContainerInput.PICKUP);
            click(mc, 45, 0, ContainerInput.PICKUP); // 45 = offhand menu slot
            click(mc, ms, 0, ContainerInput.PICKUP);
        }
    }

    // ── Legit on: instant F-swap the instant the offhand totem pops ───────

    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (!getState() || !legit.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Instant react on the crystal SPAWN packet itself -- ported from ThunderHack-Reborn's
        // AutoTotem.onPacketReceive (EntitySpawnS2CPacket branch): computing lethality and
        // arming right as the entity packet arrives is a full tick earlier than waiting for
        // EventTick.Pre's next entitiesForRendering() scan to even see the new crystal.
        if (event.packet instanceof ClientboundAddEntityPacket spawn && spawn.getType() == EntityType.END_CRYSTAL) {
            double dx = spawn.getX() - mc.player.getX(), dy = spawn.getY() - mc.player.getY(), dz = spawn.getZ() - mc.player.getZ();
            if (dx * dx + dy * dy + dz * dz <= 144.0) {
                double combined = mc.player.getHealth() + mc.player.getAbsorptionAmount();
                net.minecraft.world.phys.Vec3 pos = new net.minecraft.world.phys.Vec3(spawn.getX(), spawn.getY(), spawn.getZ());
                if (crystalDamage(pos, mc.player, combined)) armOffhand(mc, slot.getValue().intValue());
            }
            return;
        }

        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != 35) return;                 // 35 = PROTECTED_FROM_DEATH (totem pop)
        if (p.getEntity(mc.level) != mc.player) return;
        if (isTotem(mc.player.getOffhandItem())) return;   // offhand still stocked -- popped mainhand's totem

        int backupSlot = slot.getValue().intValue();
        if (isTotem(mc.player.getInventory().getItem(backupSlot)) && InvMovePlus.offhandSwapFromHotbar(mc, backupSlot)) {
            return; // vanilla F swap, zero clicks, zero delay
        }

        // Backup slot empty -- pull any totem from anywhere else in the inventory into offhand.
        int src = findTotemForBackup(mc, backupSlot);
        if (src < 0) return;
        click(mc, menuSlot(src), OFFHAND_SWAP_BUTTON, ContainerInput.SWAP);
    }
}
