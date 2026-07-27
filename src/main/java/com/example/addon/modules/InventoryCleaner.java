package com.example.addon.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ColorOption;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.render.ColorMaker;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import com.example.addon.modules.betterrekit.EvilRekit;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class InventoryCleaner extends AddonModule {
    public static final InventoryCleaner INSTANCE = new InventoryCleaner();

    public enum CleanMode { WhiteList, BlackList, All }

    public final ModeOption<CleanMode> mode = new ModeOption<>(this, "Mode",
        "WhiteList = drop items NOT in list. BlackList = drop items IN list. All = drop everything.",
        CleanMode.WhiteList);

    public final ToggleOption ignoreHotbar = new ToggleOption(this, "IgnoreHotbar",
        "Skip hotbar slots (0-8) when cleaning.", true);

    public final ToggleOption throwWorse = new ToggleOption(this, "ThrowWorse",
        "Drop lower-tier duplicate tools/armor of same type (e.g. diamond+iron pickaxe → drop iron; netherite+diamond+chain chestplate → keep netherite). Compares base tier via max durability, not remaining HP.", true);

    public final ToggleOption editWhitelist = new ToggleOption(this, "EditWhitelist",
        "Open the whitelist editor screen.", false);

    public final ColorOption whitelistAccentColor = new ColorOption(this, "WhitelistAccentColor",
        "Accent color for the whitelist editor screen (borders, dividers, hover highlight).",
        ColorMaker.staticColor(255, 255, 255), 0.24f);

    public final ToggleOption others = new ToggleOption(this, "Others",
        "Drop from other GUIs like Chest, Shulker, EnderChest...", false);

    // Per-item CUSTOM_NAME skip (shouldDrop) protects labeled gear in your own inventory.
    // 2026-07-19 ("Others bật + mở GUI shop 'Xác nhận mua' vẫn vứt/đụng item shop"): that
    // per-item filter misses shop items named via ITEM_NAME or untitled listings, so when
    // this is on the Others pass ALSO skips any container whose title isn't a vanilla
    // TranslatableContents (a server-set literal like "Xác nhận mua"), except a
    // ShulkerBoxMenu (a renamed shulker opened by hand is still cleaned).
    public final ToggleOption ignoreCustomName = new ToggleOption(this, "IgnoreCustomName",
        "Skip custom-named items, AND (with Others on) skip acting on any container with a "
        + "custom (non-vanilla) title such as a shop GUI, except shulker boxes.", false);

    public final SliderOption delay = new SliderOption(this, "Delay",
        "Tick delay between drop passes.", 1.0, 0.0, 20.0, 1.0);

    public final SliderOption actionsPerTick = new SliderOption(this, "ActionsPerTick",
        "Max items to drop per pass.", 5.0, 1.0, 20.0, 1.0);

    // ── Whitelist (static so WhitelistEditorScreen can read/write directly) ──
    public static Set<String> whitelist = new HashSet<>();

    private int ticks = 0;

    private static final Path WHITELIST_FILE =
        FabricLoader.getInstance().getConfigDir().resolve("inventory_cleaner_whitelist.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public InventoryCleaner() {
        super("InventoryCleaner", "Auto-drop unwanted items from inventory each tick.");
    }

    @Override
    public void onEnable() {
        loadWhitelist();
    }

    @Override
    public void onDisable() {
        saveWhitelist();
    }

    public static void saveWhitelist() {
        try {
            Files.writeString(WHITELIST_FILE, GSON.toJson(whitelist));
        } catch (Exception ignored) {}
    }

    public static void loadWhitelist() {
        try {
            if (Files.exists(WHITELIST_FILE)) {
                Type type = new TypeToken<Set<String>>() {}.getType();
                Set<String> loaded = GSON.fromJson(Files.readString(WHITELIST_FILE), type);
                if (loaded != null) whitelist = loaded;
            }
        } catch (Exception ignored) {}
    }

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();

        if (editWhitelist.getValue()) {
            editWhitelist.setValue(false);
            mc.execute(() -> mc.setScreen(new com.example.addon.gui.WhitelistEditorComposeScreen()));
            return;
        }

        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.isCreative()) return;

        // ── RACE GUARDS (2026-07-19: "vứt item trong list lại vứt cả item khác") ──
        // The cleaner decides which slot to THROW from client-side slot contents, but
        // EvilRekit/InventorySorter (EventTick.Post) swap items around the same slots, so
        // a slot the cleaner evaluated can hold a DIFFERENT item by the time its THROW
        // packet lands. Defer dropping while the inventory is in motion:
        // 1) Cursor non-empty -- an item is mid-move (player pickup, or a swap that left one
        //    on the cursor across a tick). THROW only fires on an empty cursor anyway, but
        //    bailing here also stops us evaluating slots whose contents are about to shift.
        if (!mc.player.containerMenu.getCarried().isEmpty()) return;
        // 2) EvilRekit or InventorySorter clicked a slot within the last 200ms -- they're
        //    actively rearranging. Only defers while they truly click; once they reach a
        //    steady state (nothing to move) the cleaner resumes normally.
        long now = System.currentTimeMillis();
        if (now - EvilRekit.lastContainerActionMs < 200 || now - InventorySorter.lastContainerActionMs < 200) return;

        // 2026-07-20 ("khi mở rương hoặc bất kì gui chứa đồ nào, InventoryCleaner cũng không
        // hoạt động"): this used to return here entirely whenever ANY external container GUI
        // was open and Others was off -- blocking the player's OWN inventory scan below too,
        // not just the external-container-slots pass. Others is documented as "drop from
        // OTHER GUIs like Chest..." -- it should only gate that separate external-slots pass
        // (further down), not the player's own inventory. Own inventory now always gets
        // scanned regardless of what screen is open.
        boolean externalGui = mc.screen instanceof AbstractContainerScreen
                           && !(mc.screen instanceof InventoryScreen);

        if (ticks < delay.getValue().intValue()) { ticks++; return; }
        ticks = 0;

        int containerId = mc.player.containerMenu.containerId;
        int actions = 0;
        int maxActions = actionsPerTick.getValue().intValue();

        // Scan inventory slots: invSlot 0-35 (0-8 = hotbar, 9-35 = main)
        for (int invSlot = 0; invSlot < 36 && actions < maxActions; invSlot++) {
            if (ignoreHotbar.getValue() && invSlot <= 8) continue;

            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) continue;

            if (shouldDrop(stack)) {
                int handlerSlot = invToHandlerSlot(mc, invSlot);
                if (handlerSlot < 0) continue;
                // button=1 with THROW drops the entire stack
                throwSlot(mc, containerId, handlerSlot);
                actions++;
            }
        }

        // ThrowWorse pass (counts against actionsPerTick budget)
        if (throwWorse.getValue() && actions < maxActions) {
            runThrowWorsePass(mc, containerId, maxActions - actions);
        }

        // Others: also drop from open container slots (chest / shulker / enderchest).
        // IgnoreCustomName additionally bars acting on a custom-titled GUI (shop), except a
        // shulker box -- see the option's javadoc.
        boolean customShopGui = ignoreCustomName.getValue() && hasCustomContainerTitle(mc)
                && !(mc.player.containerMenu instanceof ShulkerBoxMenu);
        if (externalGui && others.getValue() && !customShopGui && actions < maxActions) {
            int totalSlots = mc.player.containerMenu.slots.size();
            int containerSlotCount = totalSlots - 36; // last 36 are always player inv + hotbar
            for (int slot = 0; slot < containerSlotCount && actions < maxActions; slot++) {
                ItemStack stack = mc.player.containerMenu.slots.get(slot).getItem();
                if (stack.isEmpty()) continue;
                if (shouldDrop(stack)) {
                    throwSlot(mc, containerId, slot);
                    actions++;
                }
            }
        }
    }

    // 2026-07-20: InventoryCleaner's THROW goes through the same handleContainerInput path
    // InvMovePlus's GrimV2 mixin hooks -- intentionally NOT bypassed, so it gets the same
    // stop-before-click treatment as a real player click (deferred + replayed once the player
    // has stopped moving). See InvMovePlus.deferClick/flush.
    private void throwSlot(Minecraft mc, int containerId, int slot) {
        mc.gameMode.handleContainerInput(containerId, slot, 1, ContainerInput.THROW, mc.player);
    }

    /**
     * True when the open container's title is NOT a vanilla TranslatableContents -- i.e. a
     * server-set literal title like a shop's "Xác nhận mua". Vanilla containers are always
     * translatable, so this flags shop/search GUIs without false-positiving on real chests.
     */
    private boolean hasCustomContainerTitle(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Component title = screen.getTitle();
        if (title == null) return false;
        return !(title.getContents() instanceof TranslatableContents);
    }

    private boolean shouldDrop(ItemStack stack) {
        if (ignoreCustomName.getValue() && stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) return false;
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(stack.getItem()).toString();
        return switch (mode.getValue()) {
            case All       -> true;
            case WhiteList -> !whitelist.contains(key);
            case BlackList ->  whitelist.contains(key);
        };
    }

    private void runThrowWorsePass(Minecraft mc, int containerId, int budget) {
        // 2026-07-20 ("có giáp netherite xịn nhất, sao còn giữ giáp diamond và chain"):
        // ThrowWorse only ever compared tool suffixes -- armor pieces were never grouped at
        // all, so duplicate lower-tier armor sat in inventory untouched no matter what.
        String[] suffixes = { "_pickaxe", "_axe", "_shovel", "_hoe", "_sword",
            "_helmet", "_chestplate", "_leggings", "_boots" };
        int remaining = budget;

        for (String suffix : suffixes) {
            if (remaining <= 0) break;
            java.util.List<int[]> group = new java.util.ArrayList<>(); // [invSlot, maxDamage]
            for (int invSlot = 0; invSlot < 36; invSlot++) {
                if (ignoreHotbar.getValue() && invSlot <= 8) continue;
                ItemStack stack = mc.player.getInventory().getItem(invSlot);
                if (stack.isEmpty()) continue;
                String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
                if (key.endsWith(suffix)) {
                    group.add(new int[]{ invSlot, stack.getMaxDamage() });
                }
            }
            if (group.size() < 2) continue;

            int maxDur = group.stream().mapToInt(e -> e[1]).max().orElse(0);
            for (int[] entry : group) {
                if (remaining <= 0) break;
                if (entry[1] < maxDur) {
                    int handlerSlot = invToHandlerSlot(mc, entry[0]);
                    if (handlerSlot < 0) continue;
                    throwSlot(mc, containerId, handlerSlot);
                    remaining--;
                }
            }
        }
    }

    // 2026-07-20 ("chỉ vứt rotten flesh có sẵn trong rương, không vứt ở kho đồ khi mở rương"
    // + "di chuyển thịt thối qua lại trong rương làm vứt nhầm 1 món khác trong rương"): this
    // used to hardcode invSlot->handlerSlot assuming mc.player.containerMenu is ALWAYS the
    // player's own 36-slot InventoryMenu (hotbar at 36-44, main inv at 9-35). That's wrong the
    // instant any other menu is open (chest, shulker...) -- containerMenu is THAT menu, whose
    // slot layout differs entirely, so the computed index landed on an unrelated real slot in
    // the open container instead of the player's own item, explaining both symptoms above.
    // Fix: look up the actual Slot whose backing Container is the player's own Inventory at
    // the given index, and use ITS real index within the current menu -- correct regardless
    // of which menu is open.
    private static int invToHandlerSlot(Minecraft mc, int invSlot) {
        net.minecraft.world.Container playerInv = mc.player.getInventory();
        for (net.minecraft.world.inventory.Slot slot : mc.player.containerMenu.slots) {
            if (slot.container == playerInv && slot.getContainerSlot() == invSlot) return slot.index;
        }
        return -1; // not found -- menu doesn't expose this player-inventory slot at all
    }
}
