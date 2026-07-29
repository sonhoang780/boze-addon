package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Auto-totem. Keeps a totem in a chosen hotbar slot and re-stocks it the instant a pop
 * packet arrives (zero delay, done inside the packet-receive handler). Right-clicking
 * while holding a totem swings a golden apple into the offhand so it can be eaten. Below
 * a health threshold the hand snaps to the totem slot.
 */
public class MainHand extends AddonModule {
    public static final MainHand INSTANCE = new MainHand();

    // Hotbar has 9 slots (index 0-8); slider is 0-8 since slot 9 does not exist.
    public final SliderOption slot = new SliderOption(this, "Slot",
            "Hotbar slot (0-8) that always holds the totem.", 0.0, 0.0, 8.0, 1.0);
    public final SliderOption health = new SliderOption(this, "Health",
            "Snap the hand to the totem slot when health <= this (0 = off).", 0.0, 0.0, 36.0, 1.0);
    public final ToggleOption onFall = new ToggleOption(this, "OnFall",
            "Snap to the totem slot when predicted fall damage would be lethal.", true);
    public final ToggleOption onElytra = new ToggleOption(this, "OnElytra",
            "Snap to the totem slot while gliding.", false);
    public final ToggleOption silent = new ToggleOption(this, "Silent",
            "Danger-hold via a silent hotbar swap (hides the hand change from other players' "
            + "render) instead of a real selected-slot switch everyone sees.", false);
    public final ToggleOption crappleSpoof = new ToggleOption(this, "CrappleSpoof",
            "While egap Absorption IV is still active, eat regular gapples instead of burning egaps.", true);
    public final ToggleOption debug = new ToggleOption(this, "Debug",
            "Log totem actions for reporting.", false);

    public MainHand() {
        super("MainHand", "Instant totem re-hold on pop + apple-to-offhand + low-health hand snap.");
    }

    private void dbg(String msg) {
        if (debug.getValue()) ChatHelper.sendMsg("MainHand", msg);
    }

    // ── Debug instrumentation: desync hunt ────────────────────────────────────
    // Timestamped (ms within the minute) so lines can be correlated with a Boze
    // packetlogger capture. All no-ops unless Debug is on.

    private void dbgT(String msg) {
        if (debug.getValue()) ChatHelper.sendMsg("MainHand", "§7[" + System.currentTimeMillis() % 60000 + "] " + msg);
    }

    private String itemName(ItemStack s) {
        return s.isEmpty() ? "air" : s.getItem().toString();
    }

    /** "sel=8 main=totem off=gapple s8=totem" — one-line hand/slot snapshot. */
    private String snapshot(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        return "sel=" + inv.getSelectedSlot()
                + " main=" + itemName(mc.player.getMainHandItem())
                + " off=" + itemName(mc.player.getOffhandItem())
                + " s" + hotbarSlot() + "=" + itemName(inv.getItem(hotbarSlot()));
    }

    private String lastState = "";

    /** Log the snapshot every tick it CHANGES — catches server resyncs reverting slot 8. */
    private void dbgStateChange(Minecraft mc) {
        if (!debug.getValue()) return;
        String now = snapshot(mc);
        if (!now.equals(lastState)) {
            lastState = now;
            dbgT("state " + now);
        }
    }

    /** Outgoing packets: every carried-slot change + every container click we emit. */
    @EventHandler
    private void onPacketSend(EventPacket.Send event) {
        if (!getState() || !debug.getValue()) return;
        if (event.packet instanceof ServerboundSetCarriedItemPacket p) {
            dbgT("§bTX carried=" + p.getSlot());
        } else if (event.packet instanceof ServerboundContainerClickPacket p) {
            dbgT("§bTX click cid=" + p.containerId() + " slot=" + p.slotNum()
                    + " btn=" + p.buttonNum() + " type=" + p.containerInput());
        }
    }

    private int hotbarSlot() {
        return slot.getValue().intValue();
    }

    // InventoryMenu slot id for an inventory index (hotbar 0-8 -> 36+i, main 9-35 -> i).
    private int menuSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) return 36 + invIndex;
        return invIndex;
    }

    // True only while the player's own inventory menu is active (id 0). Cursor-based
    // moves (apple swap, offhand restore) stay gated on this; totem restock instead
    // remaps ids via menuSlotAny so it works inside foreign containers too.
    private boolean invOk(Minecraft mc) {
        return mc.player.containerMenu.containerId == 0;
    }

    // Slot id of a player-inventory index in WHATEVER menu is open — foreign containers
    // renumber player slots after their own, so scan for the slot backed by the player
    // inventory instead of assuming the id-0 layout. -1 if the menu doesn't expose it.
    private int menuSlotAny(Minecraft mc, int invIndex) {
        if (invOk(mc)) return menuSlot(invIndex);
        for (net.minecraft.world.inventory.Slot s : mc.player.containerMenu.slots) {
            if (s.container == mc.player.getInventory() && s.getContainerSlot() == invIndex) return s.index;
        }
        return -1;
    }

    private void click(Minecraft mc, int slotId, int button, ContainerInput type) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotId, button, type, mc.player);
    }

    // First inventory index in [start,end] holding item, excluding one index; -1 if none.
    private int findItem(Minecraft mc, Item item, int start, int end, int exclude) {
        Inventory inv = mc.player.getInventory();
        for (int i = start; i <= end; i++) {
            if (i == exclude) continue;
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }

    private boolean isTotem(ItemStack s) {
        return !s.isEmpty() && s.getItem() == Items.TOTEM_OF_UNDYING;
    }

    private boolean isApple(ItemStack s) {
        return s.getItem() == Items.GOLDEN_APPLE || s.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
    }

    // Health + absorption (when enabled) — what the snap/fall thresholds compare against.
    private float effectiveHealth(Minecraft mc) {
        return mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }

    // Absorption IV+ = an egap's effect is still ticking (gapple only gives I).
    private boolean hasEgapAbsorption(Minecraft mc) {
        var eff = mc.player.getEffect(MobEffects.ABSORPTION);
        return eff != null && eff.getAmplifier() > 2;
    }

    // What the offhand held before the apple swap, and the inventory slot the swap
    // parked it in; -1 = nothing pending. Restored after the apple is eaten.
    private int restoreSlot = -1;
    private Item restoreItem = null;

    // Silent mode: whether the danger-hold is currently silent-swapped in (needs swapBack once
    // the danger clears). A real selectedSlot switch doesn't need this -- it's just left selected.
    private boolean silentHeld = false;

    @Override
    public void onDisable() {
        if (silentHeld) { dev.boze.api.utility.interaction.InvHelper.swapBack(); silentHeld = false; com.example.addon.util.SilentSwapTracker.clear("MainHand"); }
        restoreSlot = -1;
        restoreItem = null;
    }

    // Swap the pre-apple offhand item back once the player is done eating (use key
    // released, no item in use). Same 3-click cursor swap as the apple move, so any
    // leftover apples land back in the slot the old item came from. Retries every
    // tick until the offhand matches again (safety net in case a click landed wrong).
    private void restoreOffhand(Minecraft mc) {
        if (restoreSlot < 0) return;
        if (mc.options.keyUse.isDown() || mc.player.isUsingItem()) return;
        ItemStack offNow = mc.player.getOffhandItem();
        if (offNow.getItem() == restoreItem) { restoreSlot = -1; restoreItem = null; return; } // already back
        if (!offNow.isEmpty() && !isApple(offNow)) { restoreSlot = -1; restoreItem = null; return; } // user chose something else; don't fight
        ItemStack parked = mc.player.getInventory().getItem(restoreSlot);
        if (parked.isEmpty() || parked.getItem() != restoreItem) {
            restoreSlot = -1; restoreItem = null; return; // user moved it; don't guess
        }
        if (restoreSlot > 8) {
            int ms = menuSlot(restoreSlot);
            click(mc, ms, 0, ContainerInput.PICKUP);
            click(mc, 45, 0, ContainerInput.PICKUP);
            click(mc, ms, 0, ContainerInput.PICKUP);
        }
        if (mc.player.getOffhandItem().getItem() == restoreItem) {
            restoreSlot = -1; restoreItem = null;
            dbg("§aoffhand restored");
        }
    }

    // Atomic swap of a spare totem into the target slot.
    //
    // instant=true (pop path only): bypass InvMovePlus for zero delay -- a popped totem
    // must be re-held THIS packet (the tick safety net below repairs it next tick anyway
    // if this races something).
    //
    // instant=false (tick safety net): go through InvMovePlus normally -- GrimV2 now
    // lies in one input packet per click instead of freezing (see InvMovePlus.deferClick),
    // so this lands immediately, no stopped-tick retry needed anymore.
    private void stockTotem(Minecraft mc, int target, boolean instant) {
        int src = findItem(mc, Items.TOTEM_OF_UNDYING, 0, 35, target);
        if (src < 0) return;
        // SWAP-with-hotbar is legal inside any container; menuSlotAny remaps the source
        // id for the open menu so restock keeps working while a shop/chest GUI is up.
        int srcId = menuSlotAny(mc, src);
        if (srcId < 0) return;
        if (instant) ControlRocket.invMoveBypass = true;
        try {
            click(mc, srcId, target, ContainerInput.SWAP);
        } finally {
            if (instant) ControlRocket.invMoveBypass = false;
        }
    }

    // ── instant totem re-hold the moment a pop packet arrives ─────────────────
    @EventHandler
    private void onPacket(EventPacket.Receive event) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Incoming desync evidence: server forcing a different held slot, or server
        // rewriting our totem slot / offhand (rejected SWAP shows up here as a revert).
        if (debug.getValue()) {
            if (event.packet instanceof ClientboundSetHeldSlotPacket held) {
                int clientSel = mc.player.getInventory().getSelectedSlot();
                dbgT("§cRX heldslot=" + held.slot()
                        + (held.slot() != clientSel ? " §4(client=" + clientSel + " DESYNC)" : ""));
            } else if (event.packet instanceof ClientboundContainerSetSlotPacket set
                    && set.getContainerId() == 0
                    && (set.getSlot() == menuSlot(hotbarSlot()) || set.getSlot() == 45)) {
                dbgT("§cRX setslot id0/" + set.getSlot() + " -> " + itemName(set.getItem()));
            }
        }

        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != 35) return;                 // 35 = PROTECTED_FROM_DEATH (totem pop)
        if (p.getEntity(mc.level) != mc.player) return;

        int target = hotbarSlot();
        if (isTotem(mc.player.getInventory().getItem(target))) return; // still stocked
        dbgT("§dPOP " + snapshot(mc));
        int src = findItem(mc, Items.TOTEM_OF_UNDYING, 0, 35, target);
        if (src < 0) { dbg("§cpop: no spare totem"); return; }
        // InvMovePlus's GrimV2 now lies in one input packet per click instead of
        // freezing movement (see InvMovePlus.deferClick) -- the click just goes through.
        stockTotem(mc, target, true);
        dbg("§apop: totem -> slot " + target);
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int target = hotbarSlot();

        dbgStateChange(mc);

        // Safety net: re-stock the totem slot the tick after a popped totem is consumed.
        // Non-instant: routed through InvMovePlus (GrimV2 lands it immediately, no
        // freeze); this handler still retries every tick until the slot is stocked, as
        // a plain safety net for anything that raced it. Runs in ANY menu (menuSlotAny
        // remap) — a shop/chest GUI doesn't block restock.
        if (!isTotem(mc.player.getInventory().getItem(target))) {
            stockTotem(mc, target, false);
            if (isTotem(mc.player.getInventory().getItem(target))) dbg("§9keep: totem -> slot " + target);
        }

        if (invOk(mc)) {
            // right-click while holding a totem -> apple to offhand
            if (mc.options.keyUse.isDown() && mc.player.getMainHandItem().getItem() == Items.TOTEM_OF_UNDYING) {
                ItemStack off = mc.player.getOffhandItem();
                if (!isApple(off)) {
                    // CrappleSpoof: egap Absorption IV still active -> refresh with a
                    // regular gapple first so egaps aren't burned (ThunderHack trick).
                    Item first = Items.ENCHANTED_GOLDEN_APPLE, second = Items.GOLDEN_APPLE;
                    if (crappleSpoof.getValue() && hasEgapAbsorption(mc)) {
                        first = Items.GOLDEN_APPLE; second = Items.ENCHANTED_GOLDEN_APPLE;
                    }
                    int appleIdx = findItem(mc, first, 0, 35, -1);
                    if (appleIdx < 0) appleIdx = findItem(mc, second, 0, 35, -1);
                    if (appleIdx >= 0) {
                        ItemStack prevOff = off.copy();
                        // Matrix swap: packet-only, movement-proof, no freeze needed
                        if (appleIdx > 8) {
                            int ms = menuSlot(appleIdx);
                            // Each click goes through InvMovePlus.deferClick (spoofed input
                            // packet if moving), so all 3 land immediately, no freeze.
                            click(mc, ms, 0, ContainerInput.PICKUP);
                            click(mc, 45, 0, ContainerInput.PICKUP);  // 45 = offhand menu slot
                            click(mc, ms, 0, ContainerInput.PICKUP);
                        }
                        // Only if the swap actually mutated: remember what the offhand
                        // held so it can be restored after eating.
                        if (!prevOff.isEmpty() && isApple(mc.player.getOffhandItem())) {
                            restoreSlot = appleIdx;
                            restoreItem = prevOff.getItem();
                        }
                        dbg("§aapple -> offhand");
                    }
                }
            }

            restoreOffhand(mc);
        }

        // danger triggers -> hold the totem slot, but only if a totem is actually
        // there; never lock the hand onto an empty/wrong slot when totems ran out.
        double hp = health.getValue();
        float eff = effectiveHealth(mc);
        boolean healthSnap = hp > 0 && eff <= hp;
        // ThunderHack fall predict: lethal-ish landing (predicted damage + 0.5 margin).
        // fallDistance > 3 gate: below that vanilla deals no damage, and without it the
        // formula would snap at low hp while standing still.
        boolean fallSnap = onFall.getValue() && mc.player.fallDistance > 3
                && eff - (((float) mc.player.fallDistance - 3f) / 2f + 3.5f) < 0.5f;
        boolean elytraSnap = onElytra.getValue() && mc.player.isFallFlying();
        boolean danger = healthSnap || fallSnap || elytraSnap;
        boolean hasTotem = isTotem(mc.player.getInventory().getItem(target));

        boolean realTotemInHand = isTotem(mc.player.getMainHandItem()) || isTotem(mc.player.getOffhandItem());
        String reason = healthSnap ? "health " + eff : fallSnap ? "fall" : "elytra";

        // Ground truth every tick, never trust the silentHeld flag alone: if danger requires a
        // totem and the REAL hand doesn't have one, escalate immediately to the guaranteed-working
        // visible switch, regardless of the Silent toggle. Reported death with Silent on + 10
        // totems in reserve = SwapType.Silent's hold silently failing to land server-side across
        // many ticks (every OTHER user of SwapType.Silent in this addon holds it for a single
        // action -- swap, place/interact, swapBack, same function call -- never open-ended like
        // this). Survival beats staying hidden.
        boolean forceVisible = danger && hasTotem && !realTotemInHand && silentHeld;

        if (silent.getValue() && !forceVisible) {
            // SwapType.Silent: hides the hand change from other players' render instead of a
            // real, everyone-sees selectedSlot switch. Held for the whole danger window (not a
            // one-shot swap+revert) since we don't know which tick the fatal hit lands.
            if (danger && hasTotem && !silentHeld) {
                ItemStack held = mc.player.getInventory().getItem(target);
                if (dev.boze.api.utility.interaction.InvHelper.swapToSlot(target, dev.boze.api.utility.interaction.SwapType.Silent)) {
                    silentHeld = true;
                    com.example.addon.util.SilentSwapTracker.set("MainHand", held);
                    dbg("§e" + reason + " -> silent hold slot " + target);
                }
            } else if ((!danger || !hasTotem) && silentHeld) {
                dev.boze.api.utility.interaction.InvHelper.swapBack();
                silentHeld = false;
                com.example.addon.util.SilentSwapTracker.clear("MainHand");
                dbg("§7danger clear -> silent release");
            }
        } else {
            if (silentHeld) {
                dev.boze.api.utility.interaction.InvHelper.swapBack();
                silentHeld = false;
                com.example.addon.util.SilentSwapTracker.clear("MainHand");
                if (forceVisible) dbg("§4silent hold didn't land -> forcing visible switch");
            }
            if (danger && hasTotem && mc.player.getInventory().getSelectedSlot() != target) {
                mc.player.getInventory().setSelectedSlot(target);
                if (mc.getConnection() != null) {
                    mc.getConnection().send(new ServerboundSetCarriedItemPacket(target));
                }
                dbg("§e" + reason + " -> hold slot " + target);
            }
        }
    }
}
