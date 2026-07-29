package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Offhand management: keeps a totem in the offhand, and swaps in an apple while
 * sword-fighting above a health threshold.
 *
 * Legit off (default): the offhand slot itself is kept topped up with a totem via a container
 * SWAP click into offhand button 40 whenever it's empty -- simple, but every refill is a click.
 *
 * Legit on: whenever the offhand isn't a totem, restock it from wherever a totem actually is,
 * hotbar first (0-8, deliberately including MainHand's own slot, so the totem in hand IS a
 * valid source), else the main inventory (9-35) -- both via a staged pickup swap
 * (swapIntoOffhandViaPickup), not the vanilla-F-key packet path. See armOffhand's own javadoc
 * for why the raw F-swap packet turned out to be the unreliable one here, not the clicks.
 */
public class BetterOffhand extends AddonModule {
    public static final BetterOffhand INSTANCE = new BetterOffhand();

    private static final int OFFHAND_SWAP_BUTTON = 40;
    private static final int OFFHAND_MENU_SLOT = 45;

    public final SliderOption health = new SliderOption(this, "Health",
            "Sword gap triggers only above this combined health+absorption "
            + "(36 = 20 max health + 16 max absorption from an enchanted golden apple).",
            20.0, 0.0, 36.0, 1.0);

    public final ToggleOption swordGap = new ToggleOption(this, "SwordGap",
            "Holding a sword and right-clicking above the Health threshold swaps the offhand "
            + "to an apple (prefers enchanted golden apple, falls back to a regular one).", false);

    public final ToggleOption legit = new ToggleOption(this, "Legit",
            "Restock the offhand totem from the hotbar via the vanilla F key (falling back to the "
            + "main inventory) instead of a direct container SWAP into the offhand.", false);

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

    private boolean isTotem(ItemStack s) {
        return !s.isEmpty() && s.getItem() == Items.TOTEM_OF_UNDYING;
    }

    private boolean isApple(ItemStack s) {
        return !s.isEmpty() && (s.getItem() == Items.GOLDEN_APPLE || s.getItem() == Items.ENCHANTED_GOLDEN_APPLE);
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

    /**
     * Staged 3-click swap into the offhand (pick up src, place into offhand grabbing whatever
     * was there, place that back into src) instead of one atomic SWAP-button click -- used for
     * the apple gap and for the inventory-totem fallback (F-swap can't reach non-hotbar slots).
     */
    private void swapIntoOffhandViaPickup(Minecraft mc, int srcIndex) {
        int ms = menuSlot(srcIndex);
        click(mc, ms, 0, ContainerInput.PICKUP);
        click(mc, OFFHAND_MENU_SLOT, 0, ContainerInput.PICKUP);
        click(mc, ms, 0, ContainerInput.PICKUP);
    }

    /**
     * Legit restock: hotbar (0-8, MainHand's slot included) first, then main inventory (9-35)
     * if the whole hotbar has no totem. Both always go through swapIntoOffhandViaPickup (real
     * container clicks via handleContainerInput) -- NOT InvMovePlus.offhandSwapFromHotbar.
     * That F-swap path sends its SWAP_ITEM_WITH_OFFHAND packet directly on the connection,
     * bypassing handleContainerInput entirely -- which means InvMovePlus's own movement-spoof
     * protection (MixinMultiPlayerGameMode's beforeClick/afterClick, hooked only into
     * handleContainerInput) never covers it. Moving while it fires -- i.e. exactly when a totem
     * matters, mid-fight -- gets it silently rejected by the server as an inventory action while
     * moving, even for the single-packet "totem already in the selected slot" case. Container
     * clicks get that same protection automatically (when InvMovePlus is on), so they're the
     * actually-reliable path here, not the "clean" one.
     */
    private void armOffhand(Minecraft mc) {
        if (isTotem(mc.player.getOffhandItem()) || onCooldown(mc)) return;
        int hotbar = findItem(mc, Items.TOTEM_OF_UNDYING, 0, 8);
        if (hotbar >= 0) { swapIntoOffhandViaPickup(mc, hotbar); return; }
        int inv = findItem(mc, Items.TOTEM_OF_UNDYING, 9, 35);
        if (inv >= 0) swapIntoOffhandViaPickup(mc, inv);
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.containerMenu.containerId != 0) return; // own inventory only

        if (!legit.getValue()) {
            if (!isTotem(mc.player.getOffhandItem())) {
                int src = findItem(mc, Items.TOTEM_OF_UNDYING, 0, 35);
                if (src >= 0) click(mc, menuSlot(src), OFFHAND_SWAP_BUTTON, ContainerInput.SWAP);
            }
        } else if (!isTotem(mc.player.getOffhandItem())) {
            armOffhand(mc);
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

        if (idx > 8) {
            swapIntoOffhandViaPickup(mc, idx);
        }
    }

    // ── Legit: instant refill the moment the offhand totem pops ───────────
    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (!getState() || !legit.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != 35) return;                 // 35 = PROTECTED_FROM_DEATH (totem pop)
        if (p.getEntity(mc.level) != mc.player) return;
        if (isTotem(mc.player.getOffhandItem())) return;   // offhand still stocked -- popped mainhand's totem
        armOffhand(mc);
    }
}
