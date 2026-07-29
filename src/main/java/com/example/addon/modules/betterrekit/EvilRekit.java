package com.example.addon.modules.betterrekit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.event.EventWorldRender;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.render.WorldDrawer;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.BreakHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import dev.boze.api.utility.interaction.InteractionMode;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvilRekit extends AddonModule {
    public static final EvilRekit INSTANCE = new EvilRekit();

    public final SliderOption delay = new SliderOption(this, "Delay", "Tick delay", 1.0, 0.0, 10.0, 1.0);
    public final SliderOption actionsPerTick = new SliderOption(this, "Frequence", "", 1.0, 1.0, 5.0, 1.0);
    public final ToggleOption auto = new ToggleOption(this, "Auto", "Auto pull shulkers from ender chest", false);
    public final ToggleOption silentContainer = new ToggleOption(this, "SilentContainer",
        "Pull items without the container GUI showing on screen.", false);
    public final ModeOption<SwapType> swapMode = new ModeOption<>(this, "SwapMode",
        "Swap type used when placing shulkers and swapping to a breaking tool in Auto mode.", SwapType.Alt);
    // findShulkerInContainer normally ranks candidates purely by matching CONTENTS --
    // two shulkers of the same color/kind can score identically (or the wrong one can
    // even score HIGHER by coincidence, e.g. holding spare kit items) even though only
    // one of them is actually the labeled kit shulker (user report, 2026-07-17: "tránh
    // lấy lộn shulker dù cùng màu"). When enabled, a shulker whose custom name matches
    // activeKitName gets an overriding bonus so it always wins regardless of content
    // score -- content score alone still decides among UNNAMED shulkers.
    public final ToggleOption considerShulkerName = new ToggleOption(this, "ConsiderShulkerName",
        "Prefer the shulker whose custom name matches the active kit's name, overriding content-based scoring.", false);
    // Manual mode pulls from ANY open container, not just the ender chest (see
    // manualPullTick's doc) -- a server shop/search GUI full of custom-named items that
    // happen to share an item TYPE with a kit slot (e.g. a "netherite pickaxe" listing
    // menu) matched purely on stack.getItem() and got clicked over and over since the
    // menu isn't a real inventory and never actually fills the slot.
    //
    // 2026-07-19 ("bật IgnoreCustomName vẫn loot con pearl ở GUI 'Xác nhận mua'"): the
    // old per-item CUSTOM_NAME filter (used to live in findBest/findExactItemInContainer)
    // missed two shop cases -- items named via ITEM_NAME rather than CUSTOM_NAME, and
    // untitled items sold in a GUI whose only "custom" signal is its TITLE. So when this
    // is on, manualPullTick bails entirely on any container whose title isn't a vanilla
    // TranslatableContents (i.e. a server-set literal title like "Xác nhận mua"), EXCEPT a
    // ShulkerBoxMenu -- a renamed shulker opened by hand still gets pulled from, since the
    // real regear flow (Auto's PULL_ITEMS) never routes through manualPullTick.
    //
    // 2026-07-25: the per-item CUSTOM_NAME filter itself removed (was findBest/
    // findExactItemInContainer's own separate check) -- it had no shulker exception at all,
    // so a saved/named kit shulker got skipped as a kit-slot candidate even inside a
    // perfectly normal-titled container (the screen-level gate above never even triggered,
    // "Large Chest" isn't a custom title) while other unnamed items in the same container
    // still worked fine. User confirmed the screen-level gate (with its shulker exception)
    // is the only layer this option needs -- item-level was redundant and strictly worse.
    public final ToggleOption ignoreCustomName = new ToggleOption(this, "IgnoreCustomName",
        "Skip any container with a custom (non-vanilla) title such as a shop GUI, except shulker boxes.", false);
    // Keybind version of the place->open->pull->close->break cycle, WITHOUT the ender
    // chest fetch/return steps (user spec, 2026-07-17: "tự đặt shulker ra, mở ra và
    // đóng vào khi rekit xong, sau đó đập đi") -- operates on whatever shulker is
    // already in the player's inventory. Reuses the SAME AutoState machine as the
    // continuous Auto toggle (PLACE_SHULKER -> OPEN_SHULKER -> PULL_ITEMS ->
    // BREAK_SHULKER -> RETURN_SHULKER), just entered directly at PLACE_SHULKER instead
    // of via FIND_SHULKER/GRAB_SHULKER. RETURN_SHULKER already no-ops straight to IDLE
    // when enderChestPos is null, so no ender-chest-specific handling was needed there.
    public final dev.boze.api.option.BindOption autoPlaceBind = new dev.boze.api.option.BindOption(
        this, "AutoPlace", "Keybind: places the shulker in your inventory, opens it, pulls kit items, "
        + "closes, then breaks it. No ender chest involved -- similar to Auto but one-shot.", -1, false);

    // Preferred enchant per gear category when several candidates of the kit's item type
    // exist in the container -- ties into scoreCandidate(), which also prefers higher
    // remaining durability for unstackables.
    public enum PickaxePref { Efficiency, SilkTouch }
    public enum ArmorPref { Blast, Prot }
    public final ModeOption<PickaxePref> pickaxePref = new ModeOption<>(this, "Pickaxe",
        "Preferred pickaxe enchant when choosing between candidates.", PickaxePref.Efficiency);
    public final ModeOption<ArmorPref> helmetPref = new ModeOption<>(this, "Helmet",
        "Preferred helmet enchant when choosing between candidates.", ArmorPref.Prot);
    public final ModeOption<ArmorPref> chestplatePref = new ModeOption<>(this, "Chestplate",
        "Preferred chestplate enchant when choosing between candidates.", ArmorPref.Prot);
    public final ModeOption<ArmorPref> leggingsPref = new ModeOption<>(this, "Leggings",
        "Preferred leggings enchant when choosing between candidates.", ArmorPref.Prot);
    public final ModeOption<ArmorPref> bootsPref = new ModeOption<>(this, "Boots",
        "Preferred boots enchant when choosing between candidates.", ArmorPref.Prot);

    public Map<Integer, KitItem> activeKit = new HashMap<>();
    public String activeKitName = "";
    private int ticks = 0;
    private final File folder;
    // Wall-clock of the last container-slot click this module sent (see click()). Read by
    // InventoryCleaner to defer its own dropping while Rekit is actively moving items, so
    // the cleaner never THROWs a slot Rekit is mid-swapping (2026-07-19 race fix).
    public static volatile long lastContainerActionMs = 0;

    // ── AUTO MODE STATE MACHINE ──
    private enum AutoState {
        IDLE,
        FIND_SHULKER,
        GRAB_SHULKER,
        PLACE_SHULKER,
        OPEN_SHULKER,
        PULL_ITEMS,
        BREAK_SHULKER,
        RETURN_SHULKER
    }

    private AutoState autoState = AutoState.IDLE;

    /**
     * Read by MixinAbstractContainerScreen to decide whether to suppress the current
     * container GUI. Must cover the WHOLE pipeline, not just the shulker box itself --
     * FIND_SHULKER/GRAB_SHULKER/RETURN_SHULKER all operate on the ender chest (a plain
     * ChestMenu), only OPEN_SHULKER/PULL_ITEMS touch the actual ShulkerBoxMenu. Gating
     * suppression on menu subtype instead of this left the ender-chest phase visibly
     * open (reported: AutoPlace with SilentContainer on still showed an empty container
     * grid) even though the shulker phase itself was correctly hidden.
     */
    public boolean isAutoActive() {
        return autoState != AutoState.IDLE;
    }
    private int autoTicks = 0;
    private int emptyTicks = 0;
    private int shulkerEnderSlot = -1;       // slot in ender chest holding the shulker
    private BlockPos placedShulkerPos = null; // world position of placed shulker
    private long placedShulkerRenderMs = 0;   // when placedShulkerPos was set -- drives the grow-in box animation
    private BlockPos enderChestPos = null;    // position of the ender chest block
    // breakingStarted now doubles as "BreakHelper.breakBlock succeeded this attempt"
    // (BreakHelper handles its own reach/tool/packet internals -- see BREAK_SHULKER).
    private boolean breakingStarted = false;
    // PLACE_SHULKER: true once ensureHotbar's SWAP packet has been given a tick to
    // settle client-side before InvHelper.swapToSlot + PlaceHelper.place() run -- see
    // its use below for why this only matters when the shulker wasn't already in the
    // hotbar (i.e. GRAB_SHULKER landed it in main inventory because the hotbar was full).
    private boolean hotbarSwapSettled = false;
    // How many times ensureHotbar's swap has been retried THIS placement attempt --
    // one settle-wait tick occasionally isn't enough (a dropped/slow packet), and the
    // old code gave up after exactly one attempt, bailing straight to RETURN_SHULKER
    // (user report, 2026-07-16: "lấy shulker để vào inventory xong lại nhét vào
    // enderchest"). Reset whenever PLACE_SHULKER is (re)entered.
    private int hotbarSwapAttempts = 0;
    private static final int MAX_HOTBAR_SWAP_ATTEMPTS = 3;
    // Hard breaker for the whole GRAB->PLACE->RETURN cycle: if placement fails this
    // many times in a row without EVER reaching OPEN_SHULKER, something is genuinely
    // stuck (no valid spot, server rejecting the swap, etc) -- looping forever is
    // worse than stopping and telling the user, and it's also why the ender chest GUI
    // never closed (it only closes via isKitComplete(), which can never become true
    // if no shulker ever actually gets placed/pulled).
    private int placeFailStreak = 0;
    private static final int MAX_PLACE_FAIL_STREAK = 3;
    // pullFromContainerTick: mirrors InventorySorter's cursorWaitTicks -- let the
    // player manually place a picked-up item themselves instead of Rekit immediately
    // dumping it into the first empty slot (user report 2026-07-15).
    private int cursorWaitTicks = 0;
    private static final int CURSOR_DUMP_AFTER_TICKS = 20; // ~1 second at 20 TPS, desync guard only
    // Hotbar-full displacement cycle (user spec, 2026-07-16): when GRAB_SHULKER finds
    // the hotbar completely full, the shulker gets SWAPPED straight into a hotbar slot,
    // shoving whatever was there into the ender chest at the shulker's old slot instead
    // of falling back to a main-inventory slot. displacedHotbarSlot/displacedEnderSlot
    // remember that pairing so RETURN_SHULKER can undo it exactly (shulker -> the exact
    // ender slot the displaced item landed in, displaced item -> back to its original
    // hotbar slot) instead of just dumping the shulker in whatever empty ender slot it
    // finds first. -1 means "not in a displacement cycle".
    private int displacedHotbarSlot = -1;
    private int displacedEnderSlot = -1;

    // 2026-07-18, "tôi nhấc item ra khỏi slot được vài giây nó lại tự đặt xuống": a kit
    // slot going correct -> incorrect can ONLY happen externally (our own logic only ever
    // ADDS the correct item to a kit slot, never removes it without immediately replacing
    // it) -- so any such transition is the player manually taking the item out. Give that
    // slot a grace window where the fill loop leaves it alone instead of instantly
    // re-yanking a replacement back into the exact slot the player just cleared; if the
    // kit still needs topping up during the grace window, it goes to a different empty
    // slot instead, per user request ("tôi phải được đặt xuống slot khác chứ").
    private final Map<Integer, Boolean> kitSlotWasCorrect = new HashMap<>();
    private final Map<Integer, Long> kitSlotClearedAtMs = new HashMap<>();
    private static final long KIT_SLOT_CLEAR_GRACE_MS = 3000L;

    private boolean autoPlaceActive = false;   // true while a bind-triggered one-shot cycle is running
    private boolean autoPlaceBindWasDown = false;

    public EvilRekit() {
        super("EvilRekit", "Better Regear");
        folder = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/evilrekit");
        if (!folder.exists()) folder.mkdirs();
        restoreLastKit();
    }

    // Auto-load the last kit that was saved/loaded, called once at class init.
    private void restoreLastKit() {
        try {
            java.io.File lastKitFile = new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
            if (!lastKitFile.exists()) return;
            String name = java.nio.file.Files.readString(lastKitFile.toPath()).trim();
            if (name.isEmpty()) return;
            File kitFile = new File(folder, name + ".json");
            if (!kitFile.exists()) return;
            Gson gson = new Gson();
            FileReader reader = new FileReader(kitFile);
            Type type = new TypeToken<Map<Integer, KitItem>>() {}.getType();
            activeKit = gson.fromJson(reader, type);
            activeKitName = name;
            reader.close();
        } catch (Exception ignored) {}
    }

    public static class KitItem {
        public String id;
        public String name;
        public int maxCount;
    }

    private void info(String msg) { ChatHelper.sendMsg("EvilRekit", "§a" + msg); }
    private void error(String msg) { ChatHelper.sendMsg("EvilRekit", "§c" + msg); }

    public List<String> getKitNames() {
        List<String> names = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".json")) {
                    names.add(f.getName().replace(".json", ""));
                }
            }
        }
        return names;
    }

    public void saveKit(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;
        Map<Integer, KitItem> kitData = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            int slot = getHandlerSlotPlayerOnly(i);
            ItemStack stack = mc.player.inventoryMenu.getSlot(slot).getItem();
            if (!stack.isEmpty()) {
                KitItem k = new KitItem();
                k.id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                k.maxCount = stack.getMaxStackSize();

                if (stack.has(DataComponents.CUSTOM_NAME)) {
                    k.name = stack.getHoverName().getString();
                }

                kitData.put(i, k);
            }
        }
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            FileWriter writer = new FileWriter(new File(folder, name + ".json"));
            gson.toJson(kitData, writer);
            writer.close();
            activeKit = kitData;
            activeKitName = name;

            java.io.File lastKitFile = new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
            java.nio.file.Files.writeString(lastKitFile.toPath(), name);

            info("Kit saved and activated: " + name);
        } catch (Exception e) {
            error("Error saving kit!");
            e.printStackTrace();
        }
    }

    public void loadKit(String name) {
        try {
            File file = new File(folder, name + ".json");
            if (!file.exists()) { error("Kit not found: " + name); return; }
            Gson gson = new Gson();
            FileReader reader = new FileReader(file);
            Type type = new TypeToken<Map<Integer, KitItem>>() {}.getType();
            activeKit = gson.fromJson(reader, type);
            activeKitName = name;

            java.io.File lastKitFile = new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
            java.nio.file.Files.writeString(lastKitFile.toPath(), name);

            reader.close();
            info("Kit loaded: " + name);
        } catch (Exception e) {
            error("Error occurred while reading kit!");
            e.printStackTrace();
        }
    }

    public void listKits() {
        File[] files = folder.listFiles();
        if (files == null || files.length == 0) { info("You don't have any kits."); return; }
        info("Available kits:");
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            String name = f.getName().replace(".json", "");
            if (name.equals(activeKitName)) {
                ChatHelper.sendMsg("EvilRekit", "§9- " + name + " [active]");
            } else {
                ChatHelper.sendMsg("EvilRekit", "§7- " + name);
            }
        }
    }

    public void showActiveKit() {
        if (activeKitName == null || activeKitName.isEmpty()) {
            error("No kit is currently active.");
        } else {
            ChatHelper.sendMsg("EvilRekit", "§aActive kit: §9" + activeKitName);
        }
    }

    public void deleteKit(String name) {
        File file = new File(folder, name + ".json");
        if (file.exists() && file.delete()) {
            if (name.equals(activeKitName)) { activeKit.clear(); activeKitName = ""; }
            info("Kit deleted: " + name);
        } else {
            error("Failed to delete kit.");
        }
    }

    @Override
    public void onEnable() {
        autoState = AutoState.IDLE;
        autoTicks = 0;
        emptyTicks = 0;
        shulkerEnderSlot = -1;
        placedShulkerPos = null;
        enderChestPos = null;
        breakingStarted = false;
        hotbarSwapSettled = false;
        cursorWaitTicks = 0;
        displacedHotbarSlot = -1;
        displacedEnderSlot = -1;
        autoPlaceActive = false;
        autoPlaceBindWasDown = false;
        kitSlotWasCorrect.clear();
        kitSlotClearedAtMs.clear();
    }

    @EventHandler
    private void onAutoPlaceBindCheck(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // isBindDown polls raw GLFW key state, bypassing Minecraft's screen/focus routing --
        // typing the bound letter into chat (or any other GUI text field) still reads as
        // "physically down" and fired the bind mid-typing. Any open screen means we're not
        // in normal gameplay input, so skip the raw poll entirely.
        if (mc.screen != null) { autoPlaceBindWasDown = false; return; }
        boolean down = isBindDown(autoPlaceBind);
        if (down && !autoPlaceBindWasDown && autoState == AutoState.IDLE
                && !mc.player.isCreative() && !mc.player.isSpectator()
                && findShulkerInInventory(mc) != -1) {
            autoPlaceActive = true;
            autoState = AutoState.PLACE_SHULKER;
            autoTicks = 0;
            hotbarSwapSettled = false;
            hotbarSwapAttempts = 0;
        }
        autoPlaceBindWasDown = down;
    }

    /**
     * BindOption exposes only getBind()/isButton() -- no "is currently pressed"
     * accessor exists in this API. Polling GLFW directly, same pattern already used
     * elsewhere in this codebase (BedAura.java, EbookReader.java, GifHUD.java).
     */
    private static boolean isBindDown(dev.boze.api.option.BindOption bindOption) {
        int code = bindOption.getBind();
        if (code < 0) return false;
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().handle();
        int state = bindOption.isButton()
            ? org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, code)
            : org.lwjgl.glfw.GLFW.glfwGetKey(handle, code);
        return state == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;

        if (auto.getValue() || autoPlaceActive) {
            handleAutoTick(mc);
            return;
        }

        // ── ORIGINAL MANUAL MODE ──
        if (!(mc.screen instanceof AbstractContainerScreen)) return;
        if (mc.screen instanceof InventoryScreen) return;
        manualPullTick(mc);
    }

    // Grow-in marker on the placed shulker: linear scale 15% -> 100% over
    // PLACE_ANIM_MS, then holds at full size for as long as placedShulkerPos stays
    // set (cleared once BREAK_SHULKER confirms the block is gone) -- a steady visual
    // of where the shulker actually landed while it's being opened/broken, not just a
    // one-shot spawn flash (user request, 2026-07-16: "box to dần từ trong ra ngoài").
    private static final long PLACE_ANIM_MS = 400;
    private static final dev.boze.api.render.ClientColor YELLOW = dev.boze.api.render.ColorMaker.staticColor(255, 220, 0);

    @EventHandler
    private void onWorldRender(EventWorldRender event) {
        if (!auto.getValue() || placedShulkerPos == null) return;
        long elapsed = System.currentTimeMillis() - placedShulkerRenderMs;
        float t = Math.min(1.0f, elapsed / (float) PLACE_ANIM_MS);
        float scale = 0.15f + 0.85f * t; // linear interpolation, small -> full block size
        double cx = placedShulkerPos.getX() + 0.5, cy = placedShulkerPos.getY() + 0.5, cz = placedShulkerPos.getZ() + 0.5;
        double half = 0.5 * scale;

        WorldDrawer.start();
        WorldDrawer.box(YELLOW, 0.25f, 0.9f, cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);
        WorldDrawer.draw(event.matrices);
    }

    /**
     * True when the open container's title is NOT a vanilla TranslatableContents -- i.e. a
     * server-set literal title like a shop's "Xác nhận mua". Vanilla containers
     * (container.chest, container.shulkerBox, container.enderchest, ...) are always
     * translatable, so this cleanly flags shop/search GUIs without false-positiving on
     * real chests. Returns false when no container screen is open.
     */
    private boolean hasCustomContainerTitle(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Component title = screen.getTitle();
        if (title == null) return false;
        return !(title.getContents() instanceof TranslatableContents);
    }

    /** One manual-mode pull pass (delay + actionsPerTick respected). Shared by manual mode
     *  and by Auto's IDLE state when the player opens a non-ender-chest container by hand. */
    private void manualPullTick(Minecraft mc) {
        if (activeKit.isEmpty()) return;
        // IgnoreCustomName: don't touch a custom-titled GUI (shop like "Xác nhận mua"),
        // except a hand-opened shulker box -- see the option's javadoc. Auto's real regear
        // (PULL_ITEMS) calls pullFromContainerTick directly, never through here, so this
        // never blocks pulling from your own renamed kit shulker during Auto.
        if (ignoreCustomName.getValue() && hasCustomContainerTitle(mc)
                && !(mc.player.containerMenu instanceof ShulkerBoxMenu)) return;
        if (ticks < delay.getValue()) { ticks++; return; }
        ticks = 0;

        int executed = 0;
        while (executed < actionsPerTick.getValue().intValue()) {
            if (!pullFromContainerTick(mc)) break;
            executed++;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AUTO MODE STATE MACHINE
    // ═══════════════════════════════════════════════════════════

    private void handleAutoTick(Minecraft mc) {
        boolean screenOpen = mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen);

        // IDLE: wait for ender chest to be opened
        if (autoState == AutoState.IDLE) {
            // Re-arms the AutoPlace bind for its next press, and is a harmless no-op
            // when IDLE was reached via the normal Auto-toggle path instead (it's
            // already false there).
            autoPlaceActive = false;
            if (screenOpen) {
                // Manually opened shulker (or any container with no ender chest around):
                // behave exactly like manual mode instead of hijacking/aborting. Auto's
                // machine only ever starts from a real ender chest. This is what made
                // "open a shulker by hand while Auto is on" do nothing before.
                if (mc.player.containerMenu instanceof net.minecraft.world.inventory.ShulkerBoxMenu) {
                    manualPullTick(mc);
                    return;
                }
                detectEnderChestPos(mc);
                if (enderChestPos == null) {
                    manualPullTick(mc); // regular chest etc. -- manual behavior, keep Auto armed
                    return;
                }
                if (activeKit.isEmpty()) {
                    auto.setValue(false);
                    return;
                }
                autoState = AutoState.FIND_SHULKER;
                autoTicks = 0;
                emptyTicks = 0;
            }
            return;
        }

        // FIND_SHULKER: scan ender chest for shulker boxes
        if (autoState == AutoState.FIND_SHULKER) {
            if (!screenOpen) return; // wait for screen
            if (isKitComplete(mc)) {
                // Same close-before-idle RETURN_SHULKER already does (its own comment,
                // 2026-07-15) -- this is the OTHER place isKitComplete can flip true:
                // if RETURN_SHULKER's last atomicSwap lands on a frame where its own
                // isKitComplete() check reads stale (pre-settle) slot state, it takes
                // the "loop: find next shulker" branch instead of closing, and control
                // arrives HERE next tick with the kit now actually complete -- but
                // this branch never closed the screen, leaving the ender chest (and
                // whatever inventory-adjacent GUI was stacked with it) open forever
                // (user report, 2026-07-16: "nhét được shulker vào enderchest... vẫn
                // không đóng được cái Gui").
                //
                // Every close in this class now uses player.closeContainer() -- NOT
                // mc.setScreen(null). setScreen(null) only calls Screen.removed()
                // (client-side widget cleanup); it never sends
                // ServerboundContainerClosePacket, so the SERVER's player.containerMenu
                // stayed pointed at the ender chest menu forever. The next real
                // container click (opening your own inventory and clicking an item)
                // got silently dropped server-side as "Ignoring click in mismatching
                // container" -- the exact same rejection already verified this session
                // in MultiPlayerGameMode.handleContainerInput's bytecode.
                // closeContainer() sends the close packet AND resets local state
                // (verified via javap: LocalPlayer.closeContainer() ->
                // clientSideCloseContainer() -> also calls setScreen(null) itself, so
                // this one call fully replaces the old one) (user report, 2026-07-16:
                // "mở lại inventory, tôi không thể thao tác với items ... chỉ fixed
                // khi đóng inventory và mở lại").
                mc.player.closeContainer();
                autoState = AutoState.IDLE;
                return;
            }
            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize <= 0) return;
            shulkerEnderSlot = findShulkerInContainer(mc, containerSize);
            if (shulkerEnderSlot == -1) {
                // No shulker in the chest holds anything the kit still needs, yet the kit
                // isn't complete (isKitComplete was checked above) -- genuinely unfillable.
                // Leaving autoState=IDLE with the screen still OPEN made IDLE's own
                // screenOpen branch immediately re-enter FIND_SHULKER next tick: an infinite
                // FIND_SHULKER<->IDLE spin that never closed the ender chest+inventory GUI
                // (user report, 2026-07-16: "cất được shulker vào inventory rồi mà không
                // đóng nổi cái gui"). Close it and stop instead of spinning.
                error("Auto stopped: no shulker has the items this kit still needs.");
                mc.player.closeContainer(); // NOT setScreen(null) -- see FIND_SHULKER's isKitComplete branch for why
                autoState = AutoState.IDLE;
                auto.setValue(false);
                return;
            }
            autoState = AutoState.GRAB_SHULKER;
            autoTicks = 0;
            return;
        }

        // GRAB_SHULKER: take shulker from ender chest into player inventory
        if (autoState == AutoState.GRAB_SHULKER) {
            if (!screenOpen) return;
            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize <= 0) return;
            AbstractContainerMenu handler = mc.player.containerMenu;

            // verify shulker still in that slot
            ItemStack slotStack = handler.getSlot(shulkerEnderSlot).getItem();
            if (!isShulkerBox(slotStack)) {
                autoState = AutoState.FIND_SHULKER;
                return;
            }

            // Prefer moving the shulker straight into an EMPTY HOTBAR slot with a SWAP
            // click on the STILL-OPEN ender chest menu -- the server accepts it because
            // that container is genuinely open, so the shulker is guaranteed in the hotbar
            // before the screen ever closes. The old path grabbed into any empty slot
            // (often MAIN inventory when the hotbar was busy) and relied on ensureHotbar's
            // SWAP against the CLOSED inventoryMenu (id 0) afterward -- which the server
            // rejected as a container mismatch (its own player.containerMenu was still the
            // ender chest for that in-flight moment), so the shulker never actually reached
            // the hotbar server-side and placement read an empty main hand (user report,
            // 2026-07-16: "lấy shulker vào người rồi mà không đặt ra được, không biết đổi
            // từ inventory về hot bar"). ContainerInput.SWAP: slotId = source ender-chest
            // slot, button = destination hotbar index 0-8 (verified against
            // AbstractContainerMenu.clicked bytecode: SWAP requires button in [0,9) or 40
            // and swaps player Inventory.getItem(button) with slots.get(slotId)).
            int emptyHotbar = firstEmptyHotbarSlot(mc);
            if (emptyHotbar != -1) {
                mc.gameMode.handleContainerInput(handler.containerId, shulkerEnderSlot, emptyHotbar, ContainerInput.SWAP, mc.player);
            } else {
                // Hotbar completely full -- SWAP the shulker straight into a hotbar slot on
                // the STILL-OPEN ender chest menu, displacing whatever's there into the
                // ender chest at the shulker's old slot. Placing the shulker then empties
                // that hotbar slot naturally -- exactly the "always leave 1 slot free to
                // pick the shulker back up" the user specced, since it's the slot the shulker
                // itself just vacated. RETURN_SHULKER's displaced-item branch restores the
                // original item to that same hotbar slot once the cycle is done.
                int hotbarSlot = mc.player.getInventory().getSelectedSlot() == 0 ? 1 : 0;
                mc.gameMode.handleContainerInput(handler.containerId, shulkerEnderSlot, hotbarSlot, ContainerInput.SWAP, mc.player);
                displacedHotbarSlot = hotbarSlot;
                displacedEnderSlot = shulkerEnderSlot;
            }
            autoState = AutoState.PLACE_SHULKER;
            autoTicks = 0;
            hotbarSwapSettled = false;
            hotbarSwapAttempts = 0;
            return;
        }

        // PLACE_SHULKER: close ender chest screen, place shulker on ground
        if (autoState == AutoState.PLACE_SHULKER) {
            if (screenOpen) {
                mc.player.closeContainer(); // NOT setScreen(null) -- see FIND_SHULKER's isKitComplete branch for why
                return; // wait next tick after closing
            }

            // Wait a tick for screen close to propagate. NOT a network-latency wait --
            // user confirmed singleplayer (zero real RTT) still hit this, ruling out
            // the server-race theory as the actual cause; see findPlaceableSpot's
            // enderChestPos exclusion for the real root cause of "shulker won't
            // place" (2026-07-16). hotbarSwapAttempts below still covers whatever
            // genuine multiplayer lag exists.
            if (autoTicks < 1) { autoTicks++; return; }

            // find the shulker in inventory
            int shulkerInvSlot = findShulkerInInventory(mc);
            if (shulkerInvSlot == -1) {
                autoState = AutoState.IDLE;
                return;
            }

            // Normal swap (below) = Inventory.setSelectedSlot, which CRASHES on slots >8
            // ("Invalid selected slot") -- move the shulker into the hotbar first if it
            // isn't there. ensureHotbar sends its OWN container-click packet (separate
            // from the swapToSlot+place below); when GRAB_SHULKER had to land the
            // shulker outside the hotbar (hotbar was full of other items, so
            // findEmptyUnassignedHandlerSlot skipped straight to main inventory), that
            // packet needs a tick to actually settle in the local menu/inventory before
            // PlaceHelper.place() reads the main-hand item -- placing in the SAME tick
            // read the stale (pre-swap) held item and silently failed (user report,
            // 2026-07-15: "không đặt được shulker khi hotbar đã full slot"). Skipped
            // entirely when the shulker is already in the hotbar (no swap needed).
            if (shulkerInvSlot > 8 && !hotbarSwapSettled) {
                ensureHotbar(mc, shulkerInvSlot);
                hotbarSwapSettled = true;
                return; // let the swap settle; retry next tick
            }

            // The settle-wait fires once per attempt (hotbarSwapSettled), but the
            // shulker can still be outside the hotbar afterward (a slow/dropped
            // packet) -- re-arm and retry rather than immediately giving up, up to
            // MAX_HOTBAR_SWAP_ATTEMPTS times.
            if (shulkerInvSlot > 8) {
                hotbarSwapAttempts++;
                if (hotbarSwapAttempts > MAX_HOTBAR_SWAP_ATTEMPTS) {
                    placeFailStreak++;
                    autoState = AutoState.RETURN_SHULKER;
                    return;
                }
                hotbarSwapSettled = false; // re-arm for another attempt next tick
                return;
            }

            // find placeable spot near player
            BlockPos placePos = findPlaceableSpot(mc);
            if (placePos == null) {
                placeFailStreak++;
                autoState = AutoState.RETURN_SHULKER; // try to return it
                return;
            }

            // swap shulker to hand and place
            BlockHitResult hit = getHitResultForPlace(mc, placePos);
            if (hit == null) {
                placeFailStreak++;
                autoState = AutoState.RETURN_SHULKER;
                return;
            }

            // Placing a block needs the shulker genuinely in the local main hand -- Alt/Silent
            // swap types are illusion-swaps (fool the server/observers, don't reliably change
            // what PlaceHelper actually sees locally), which is why the shulker never landed
            // and OPEN_SHULKER kept timing out with "Could not open placed shulker". Placement
            // always uses a real (Normal) swap regardless of the configured SwapMode.
            // By now the shulker is guaranteed in the hotbar (either it started there, or
            // the settle-wait above already moved it and gave it a tick to apply).
            shulkerInvSlot = findShulkerInInventory(mc);
            if (shulkerInvSlot < 0 || shulkerInvSlot > 8) {
                placeFailStreak++;
                autoState = AutoState.RETURN_SHULKER; // still not in hotbar -- bail rather than crash setSelectedSlot
                return;
            }
            InvHelper.swapToSlot(shulkerInvSlot, SwapType.Normal);
            // PlaceHelper's own docs recommend isEmpty(pos) before casting and its
            // place() return value tells you whether it actually happened -- this
            // code checked neither. A candidate close to the player (small ±2 search
            // radius near an obstacle-dense spot, e.g. standing right at the ender
            // chest) can pass findPlaceableSpot's block-state filter yet still be
            // occupied by an ENTITY (most commonly the player's OWN hitbox bleeding
            // into an adjacent tile) -- place() then silently fails server-side, but
            // the old code barreled on into OPEN_SHULKER anyway, right-clicking empty
            // air for 60 ticks, then BREAK_SHULKER's "block already gone" branch, then
            // RETURN_SHULKER -- burning ~100+ ticks to fail every time near the chest
            // (user report, 2026-07-16). Checking the actual result fails fast instead.
            boolean placed = PlaceHelper.place(InteractionMode.NCP, hit, InteractionHand.MAIN_HAND);
            if (!placed) {
                InvHelper.swapBack(); // abandoning this attempt -- restore the held item now
                placeFailStreak++;
                autoState = AutoState.RETURN_SHULKER;
                return;
            }

            placedShulkerPos = placePos;
            placedShulkerRenderMs = System.currentTimeMillis(); // starts the grow-in box animation, see onWorldRender
            autoState = AutoState.OPEN_SHULKER;
            autoTicks = 0;
            return;
        }

        // OPEN_SHULKER: right-click the placed shulker to open its container.
        // Retries every few ticks until screen opens, then transitions to PULL_ITEMS.
        if (autoState == AutoState.OPEN_SHULKER) {
            if (screenOpen) {
                // screen opened — swap back and transition to pull
                InvHelper.swapBack();
                autoState = AutoState.PULL_ITEMS;
                autoTicks = 0;
                emptyTicks = 0;
                placeFailStreak = 0; // a shulker actually got placed+opened -- not stuck
                return;
            }

            // Try immediately (tick 0), not after a settle delay -- shrinks the window a
            // high-efficiency tool has to break the shulker before it's opened. Worst case
            // the block hasn't appeared client-side yet and this attempt is a no-op; the
            // retry loop below (every 4 ticks) still covers that.
            if (autoTicks == 0 || autoTicks % 4 == 0) {
                BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(placedShulkerPos),
                    Direction.UP,
                    placedShulkerPos,
                    false
                );
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            }

            autoTicks++;
            if (autoTicks > 60) {
                InvHelper.swapBack(); // restore hand before breaking
                autoState = AutoState.BREAK_SHULKER;
                autoTicks = 0;
            }
            return;
        }

        // PULL_ITEMS: let existing pullFromContainerTick logic run on the shulker container
        if (autoState == AutoState.PULL_ITEMS) {
            if (!screenOpen) {
                // screen was closed during pull (finished or user closed)
                autoState = AutoState.BREAK_SHULKER;
                autoTicks = 0;
                return;
            }

            if (activeKit.isEmpty()) {
                autoState = AutoState.BREAK_SHULKER;
                autoTicks = 0;
                return;
            }

            if (ticks < delay.getValue()) { ticks++; return; }
            ticks = 0;

            boolean didWork = false;
            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize > 0) {
                int executed = 0;
                while (executed < actionsPerTick.getValue().intValue()) {
                    if (!pullFromContainerTick(mc)) break;
                    executed++;
                    didWork = true;
                }
            }

            if (didWork) {
                emptyTicks = 0;
            } else {
                emptyTicks++;
            }

            // 5 consecutive empty ticks → pulling done
            if (emptyTicks >= 5) {
                autoState = AutoState.BREAK_SHULKER;
                autoTicks = 0;
                breakingStarted = false;
            }
            return;
        }

        // BREAK_SHULKER: close screen, break the placed shulker block
        if (autoState == AutoState.BREAK_SHULKER) {
            if (screenOpen) {
                mc.player.closeContainer(); // NOT setScreen(null) -- see FIND_SHULKER's isKitComplete branch for why
                return;
            }

            if (autoTicks < 2) { autoTicks++; return; } // wait for screen close

            if (placedShulkerPos == null || mc.level == null) {
                autoState = AutoState.IDLE;
                return;
            }

            // check if block is already gone
            if (mc.level.getBlockState(placedShulkerPos).canBeReplaced()) {
                // block broken — actively poll for pickup instead of a blind short wait.
                // Moving straight to RETURN_SHULKER/FIND_SHULKER before the item is actually
                // in the inventory abandons it on the ground and grabs a fresh shulker from
                // the ender chest instead, silently losing whatever was in the first one.
                if (findShulkerInInventory(mc) == -1) {
                    if (autoTicks < 100) { autoTicks++; return; } // ~5s to walk over/collect it
                    // give up waiting, but still attempt RETURN_SHULKER in case it's just late
                }
                autoState = AutoState.RETURN_SHULKER;
                autoTicks = 0;
                return;
            }

            // timeout: 200 ticks (10 seconds) for breaking
            if (autoTicks > 200) {
                autoState = AutoState.RETURN_SHULKER; // try to return (shulker still placed)
                autoTicks = 0;
                return;
            }

            // BreakHelper.breakBlock: packet-based break (dev.boze.api.utility.
            // interaction.BreakHelper), replacing the old manual startDestroyBlock/
            // continueDestroyBlock survival-mining simulation (user request,
            // 2026-07-16: "thử dùng BreakHelper for block breaking").
            //
            // REGRESSION FIXED (2026-07-16, same day): the first version gated this
            // behind "if (!breakingStarted)", calling breakBlock() exactly ONCE per
            // BREAK_SHULKER entry and never again -- breakBlock()'s return value means
            // "a break attempt was sent this call", NOT "the block is now broken", so
            // one failed/incomplete attempt (reach edge, timing, whatever) latched
            // breakingStarted=true FOREVER with the shulker still standing, silently
            // idling for the full 200-tick timeout, then bouncing to RETURN_SHULKER,
            // finding the block still placed, bouncing BACK to BREAK_SHULKER -- an
            // infinite oscillation that never actually broke the block (user report,
            // 2026-07-16, screenshot showing the placement marker still on an intact
            // shulker: "shulker không hề vỡ"). Call it every tick, like the old
            // continueDestroyBlock loop did, until the canBeReplaced() check above
            // catches the real break.
            breakingStarted = BreakHelper.breakBlock(placedShulkerPos);
            autoTicks++;
            return;
        }

        // RETURN_SHULKER: put shulker back into ender chest
        if (autoState == AutoState.RETURN_SHULKER) {
            if (enderChestPos == null) {
                autoState = AutoState.IDLE;
                return;
            }

            if (!screenOpen) {
                if (autoTicks < 2) { autoTicks++; return; }

                // try to re-open ender chest (once at autoTicks == 2)
                if (autoTicks == 2) {
                    BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(enderChestPos),
                        Direction.UP,
                        enderChestPos,
                        false
                    );
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                }

                autoTicks++;
                if (autoTicks > 40) {
                    autoState = AutoState.IDLE;
                }
                return;
            }
            autoTicks = 0; // screen opened, reset tick counter

            // ender chest is open, find shulker in inventory and put it back
            int shulkerInvSlot = findShulkerInInventory(mc);
            if (shulkerInvSlot == -1) {
                // no shulker in inventory (might still be placed as a block)
                if (placedShulkerPos != null && mc.level != null && !mc.level.getBlockState(placedShulkerPos).canBeReplaced()) {
                    // shulker still placed, need to break it first
                    autoState = AutoState.BREAK_SHULKER;
                    autoTicks = 0;
                    breakingStarted = false;
                    return;
                }
                autoState = AutoState.FIND_SHULKER;
                autoTicks = 0;
                return;
            }

            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize <= 0) return;
            AbstractContainerMenu handler = mc.player.containerMenu;

            if (displacedHotbarSlot != -1) {
                // Undo the hotbar-full displacement: put the shulker back into the EXACT
                // ender slot the displaced item landed in, then move that item back to its
                // original hotbar slot. A straight 2-slot swap can't do this 3-way
                // relocation (shulker's current inventory slot -> ender slot -> hotbar
                // slot), so it's done as a 3-click PICKUP rotation, same primitive
                // atomicSwap uses. Relies on displacedHotbarSlot being empty at this point
                // (it is, by design -- the shulker vacated it on placement and nothing else
                // in this state machine writes there).
                int shulkerHandlerSlot = getPlayerHandlerSlot(containerSize, shulkerInvSlot);
                click(mc, handler.containerId, shulkerHandlerSlot, 0, ContainerInput.PICKUP); // cursor = shulker
                click(mc, handler.containerId, displacedEnderSlot, 0, ContainerInput.PICKUP);  // ender slot = shulker, cursor = displaced item
                int origHandlerSlot = getPlayerHandlerSlot(containerSize, displacedHotbarSlot);
                click(mc, handler.containerId, origHandlerSlot, 0, ContainerInput.PICKUP);     // hotbar slot = displaced item, cursor empty
                displacedHotbarSlot = -1;
                displacedEnderSlot = -1;
            } else {
                // find empty slot in ender chest
                int emptyEnderSlot = -1;
                for (int i = 0; i < containerSize; i++) {
                    if (handler.getSlot(i).getItem().isEmpty()) {
                        emptyEnderSlot = i;
                        break;
                    }
                }
                if (emptyEnderSlot == -1) {
                    autoState = AutoState.IDLE;
                    return;
                }

                int playerHandlerSlot = getPlayerHandlerSlot(containerSize, shulkerInvSlot);
                atomicSwap(mc, handler.containerId, playerHandlerSlot, emptyEnderSlot);
            }

            placedShulkerPos = null;
            shulkerEnderSlot = -1;
            // Hard breaker: this shulker just went back into the chest UNPLACED
            // (placeFailStreak only survives this far when it never reached
            // OPEN_SHULKER) -- looping back into FIND_SHULKER would just grab the
            // same shulker again and repeat forever, which is also why the chest GUI
            // never closed (isKitComplete() can never go true if nothing ever gets
            // placed/pulled). Stop and tell the user instead of spinning (user report,
            // 2026-07-16: "infinite loop", "vẫn không đóng được gui enderchest").
            if (placeFailStreak >= MAX_PLACE_FAIL_STREAK) {
                error("Auto stopped: could not place/open a shulker after " + placeFailStreak + " attempts.");
                mc.player.closeContainer(); // NOT setScreen(null) -- see FIND_SHULKER's isKitComplete branch for why
                autoState = AutoState.IDLE;
                auto.setValue(false);
                placeFailStreak = 0;
            } else if (isKitComplete(mc)) {
                // Every other transition out of an open container closes the screen
                // before moving on (PLACE_SHULKER, BREAK_SHULKER) -- this final one
                // didn't, so the ender chest was left open forever after a completed
                // regear (user report, 2026-07-15). IDLE's own screenOpen branch would
                // otherwise see it still open and immediately restart FIND_SHULKER.
                mc.player.closeContainer(); // NOT setScreen(null) -- see FIND_SHULKER's isKitComplete branch for why
                autoState = AutoState.IDLE;
            } else {
                autoState = AutoState.FIND_SHULKER; // loop: find next shulker
            }
            autoTicks = 0;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AUTO HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    private void detectEnderChestPos(Minecraft mc) {
        // try hitResult first
        if (mc.hitResult instanceof BlockHitResult) {
            BlockHitResult bhr = (BlockHitResult) mc.hitResult;
            if (mc.level.getBlockState(bhr.getBlockPos()).is(Blocks.ENDER_CHEST)) {
                enderChestPos = bhr.getBlockPos();
                return;
            }
        }
        // scan nearby blocks
        BlockPos playerPos = mc.player.blockPosition();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (mc.level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
                        enderChestPos = pos;
                        return;
                    }
                }
            }
        }
    }

    // Picks the shulker whose PACKED CONTENTS (read via DataComponents.CONTAINER,
    // no need to open it) best match the currently loaded kit -- highest count of
    // matching item types wins, with unstackable matches (maxStackSize == 1, e.g.
    // tools/armor) weighted far above stackables so a shulker holding kit gear is
    // always preferred over one holding e.g. spare blocks of a kit item.
    private int findShulkerInContainer(Minecraft mc, int containerSize) {
        AbstractContainerMenu handler = mc.player.containerMenu;
        // Only item types for kit slots that are STILL wrong/empty -- a shulker holding
        // an item the kit needs somewhere is worthless if every slot wanting that item is
        // already correctly filled. Without this filter, findShulkerInContainer kept
        // scoring >0 (and Auto kept pulling shulkers forever) purely because the item type
        // existed in the kit ANYWHERE, even when nothing left to fill actually needed it.
        java.util.Set<Item> kitItems = new java.util.HashSet<>();
        for (Map.Entry<Integer, KitItem> entry : activeKit.entrySet()) {
            ItemStack playerStack = mc.player.getInventory().getItem(entry.getKey());
            if (!isCorrectItem(playerStack, entry.getValue())) {
                kitItems.add(BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.getValue().id)));
            }
        }

        int bestSlot = -1;
        int bestScore = -1;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!isShulkerBox(stack)) continue;

            int score = 0;
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) {
                for (ItemStack inner : (Iterable<ItemStack>) contents.nonEmptyItemCopyStream()::iterator) {
                    if (kitItems.contains(inner.getItem())) {
                        score += inner.getMaxStackSize() == 1 ? 100 : 1;
                    }
                }
            }
            // Overriding bonus (dwarfs any possible content score) for a shulker whose
            // custom name matches the active kit -- see considerShulkerName's javadoc.
            // NFKC-normalize both sides first: a "custom font" shulker name is usually not
            // a font/style change at all (Style.font only swaps the GLYPH, getString()'s
            // literal characters are unaffected by that) -- it's actual different Unicode
            // codepoints (Mathematical Alphanumeric Symbols "𝐊𝐢𝐭", fullwidth "Ｋｉｔ", etc.)
            // that render as fancy lookalikes. Plain equalsIgnoreCase against a normally-
            // typed kit name never matches those. NFKC compatibility decomposition folds
            // exactly these stylized-letter blocks back to their base Latin form, so a
            // fancy-font shulker name matches the plain-typed kit name like it visually
            // should (user report, 2026-07-25: "không hoạt động với các shulker có name
            // với chữ font custom").
            if (considerShulkerName.getValue() && !activeKitName.isEmpty()
                    && stack.has(DataComponents.CUSTOM_NAME)
                    && normalizeName(stack.getHoverName().getString()).equalsIgnoreCase(normalizeName(activeKitName))) {
                score += 1_000_000;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        // A shulker holding NOTHING the kit still needs is useless -- returning it here
        // meant Auto never noticed a kit slot was unfillable (no shulker anywhere has that
        // item) and just kept pulling shulker after shulker forever instead of stopping.
        if (bestScore <= 0) return -1;
        return bestSlot;
    }

    /**
     * Guarantees the item at invSlot ends up in the hotbar, returning its hotbar slot
     * (0-8). Non-hotbar slots get container-SWAPPED into the currently selected hotbar
     * slot. Only valid with NO external container open (uses inventoryMenu) -- both
     * call sites (PLACE_SHULKER, BREAK_SHULKER) run after the screen is closed.
     */
    private int ensureHotbar(Minecraft mc, int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return invSlot;
        int hotbar = mc.player.getInventory().getSelectedSlot();
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
            getHandlerSlotPlayerOnly(invSlot), hotbar, ContainerInput.SWAP, mc.player);
        return hotbar;
    }

    private int findShulkerInInventory(Minecraft mc) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isShulkerBox(stack)) return i;
        }
        return -1;
    }

    /** First empty hotbar slot (0-8), or -1 if the hotbar is full. */
    private int firstEmptyHotbarSlot(Minecraft mc) {
        for (int i = 0; i <= 8; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    // 2026-07-18, "nếu có player đang pvp với tôi thì đặt xa player đó ra": vanilla's own
    // combat tracker (LivingEntity.getLastHurtByMob/getLastHurtByMobTimestamp) already
    // tracks "who am I actively fighting" -- no custom damage-event wiring needed. A hit
    // older than this window means the fight's likely over; fall back to nearest-to-self.
    private static final long RECENT_COMBAT_TICKS = 100L; // ~5s at 20 TPS

    private net.minecraft.world.entity.player.Player getActiveCombatOpponent(Minecraft mc) {
        net.minecraft.world.entity.LivingEntity last = mc.player.getLastHurtByMob();
        if (!(last instanceof net.minecraft.world.entity.player.Player opponent)) return null;
        long ticksSinceHit = mc.level.getGameTime() - mc.player.getLastHurtByMobTimestamp();
        return ticksSinceHit <= RECENT_COMBAT_TICKS ? opponent : null;
    }

    private BlockPos findPlaceableSpot(Minecraft mc) {
        BlockPos base = mc.player.blockPosition();
        net.minecraft.world.entity.player.Player opponent = getActiveCombatOpponent(mc);
        // Search out to the player's real interact reach (not a fixed +-2) -- user
        // explicitly doesn't need it near themselves, only openable, so widening this is
        // required for "away from the opponent" to have anywhere meaningful to go.
        int r = (int) Math.ceil(mc.player.blockInteractionRange());

        // Raster scan order used to return the FIRST valid candidate hit, which is very
        // often a corner of the search box even when the tile right at the player's feet
        // was already valid -- placed shulker ended up needlessly far, forcing a walk to
        // pick it back up after breaking (user report, 2026-07-16). Collect every valid
        // candidate and pick by actual distance instead of scan order: nearest-to-self by
        // default, or FARTHEST-from-opponent when actively being fought (still gated by
        // the reach radius above, so it stays within interact range of the player).
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos candidate = base.offset(dx, 0, dz);
                if (candidate.distSqr(base) > r * (double) r) continue;
                // Never place ON TOP of the ender chest itself -- its top face passes
                // the "solid block below" check below just like any other block, so
                // without this exclusion the search radius (which always covers the
                // chest the player is standing next to) could pick that exact spot.
                // getHitResultForPlace then targets the chest's own UP face, and
                // right-clicking a block with its own GUI (the ender chest) OPENS IT
                // instead of placing the held shulker -- which is exactly "won't place
                // while still in range to open the ender chest" (user report,
                // 2026-07-16), and the unexpected reopened ender-chest screen is also
                // why Rekit could never cleanly close it afterward.
                if (candidate.below().equals(enderChestPos)) continue;
                // PlaceHelper's own docs: "recommended to check isEmpty before
                // casting" -- catches ENTITIES occupying the tile (most commonly the
                // player's own hitbox bleeding into an adjacent candidate at small
                // search radius, e.g. standing right next to the ender chest), which
                // the raw block-state checks below can't see at all (2026-07-16).
                if (!PlaceHelper.isEmpty(candidate)) continue;
                // need air at candidate AND solid block below
                if (mc.level.getBlockState(candidate).canBeReplaced()
                    && !mc.level.getBlockState(candidate.below()).canBeReplaced()
                    && !mc.level.getBlockState(candidate.below()).is(Blocks.AIR)) {
                    // also need air above for shulker to open (shulker is 1 block tall)
                    if (mc.level.getBlockState(candidate.above()).canBeReplaced()) {
                        double score = opponent != null
                            ? -candidate.distSqr(opponent.blockPosition()) // maximize distance from opponent
                            : dx * dx + dz * dz;                          // minimize distance from self
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    private BlockHitResult getHitResultForPlace(Minecraft mc, BlockPos placePos) {
        // click the block below to place on top of it
        BlockPos target = placePos.below();
        if (mc.level.getBlockState(target).canBeReplaced()) {
            // try horizontal neighbors
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP || dir == Direction.DOWN) continue;
                BlockPos neighbour = placePos.relative(dir.getOpposite());
                if (!mc.level.getBlockState(neighbour).canBeReplaced()) {
                    Vec3 hitVec = Vec3.atCenterOf(neighbour).add(
                        dir.getStepX() * 0.5,
                        dir.getStepY() * 0.5,
                        dir.getStepZ() * 0.5);
                    return new BlockHitResult(hitVec, dir, neighbour, false);
                }
            }
            return null;
        }
        return new BlockHitResult(
            Vec3.atCenterOf(placePos).add(0, -0.5, 0),
            Direction.UP,
            target,
            false
        );
    }

    private int findEmptyUnassignedHandlerSlot(AbstractContainerMenu handler, int containerSize) {
        // ponytail: grab shulker into ANY empty slot, not just kit-unassigned ones.
        // pullFromContainerTick already skips slots containing shulker boxes via isShulkerBox() continue.
        for (int i = 0; i < 36; i++) {
            int slot = getPlayerHandlerSlot(containerSize, i);
            if (handler.getSlot(slot).getItem().isEmpty()) return slot;
        }
        return -1;
    }

    private boolean isKitComplete(Minecraft mc) {
        if (activeKit.isEmpty()) return true;
        for (Map.Entry<Integer, KitItem> entry : activeKit.entrySet()) {
            int invSlot = entry.getKey();
            KitItem kit = entry.getValue();
            ItemStack playerStack = mc.player.getInventory().getItem(invSlot);
            if (!isCorrectItem(playerStack, kit)) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════
    // EXISTING PULL LOGIC (unchanged)
    // ═══════════════════════════════════════════════════════════

    private boolean pullFromContainerTick(Minecraft mc) {
        AbstractContainerMenu handler = mc.player.containerMenu;
        int containerSize = handler.slots.size() - 36;
        if (containerSize <= 0) return false;

        if (!handler.getCarried().isEmpty()) {
            // Let the player manually click an item, pick it up, and place it themselves
            // -- Rekit must not auto-dump whatever's on the cursor the instant they pick
            // something up (user report, 2026-07-15; same fix as InventorySorter's
            // cursorWaitTicks). Only dump as a desync guard after it's sat there for a
            // while (e.g. a server-rejected click left OUR OWN cursor stuck, not the
            // player's manual pickup).
            cursorWaitTicks++;
            if (cursorWaitTicks < CURSOR_DUMP_AFTER_TICKS) return false;
            cursorWaitTicks = 0;

            int clearSlot = -1;
            for (int i = 0; i < containerSize; i++) {
                if (handler.getSlot(i).getItem().isEmpty()) { clearSlot = i; break; }
            }
            if (clearSlot == -1) {
                for (int i = 0; i < 36; i++) {
                    int slot = getPlayerHandlerSlot(containerSize, i);
                    if (handler.getSlot(slot).getItem().isEmpty()) { clearSlot = slot; break; }
                }
            }
            if (clearSlot != -1) {
                click(mc, handler.containerId, clearSlot, 0, ContainerInput.PICKUP);
                return true; // cursor cleared, try pulling next tick
            }
            return false; // nowhere to dump carried item
        }
        cursorWaitTicks = 0;

        for (int i = 0; i < 36; i++) {
            KitItem kit = activeKit.get(i);
            if (kit == null) continue;

            int playerSlot = getPlayerHandlerSlot(containerSize, i);
            ItemStack playerStack = handler.getSlot(playerSlot).getItem();

            boolean correctNow = isCorrectItem(playerStack, kit);
            // A kit slot going correct -> incorrect can only be the player's own action (see
            // field doc above) -- start/refresh this slot's grace window on that transition.
            if (Boolean.TRUE.equals(kitSlotWasCorrect.get(i)) && !correctNow) {
                kitSlotClearedAtMs.put(i, System.currentTimeMillis());
            }
            kitSlotWasCorrect.put(i, correctNow);

            if (!correctNow) {
                // Shulker-as-wrong-item (compensation case) is handled by the loop below --
                // only skip that case here, not the "already correct, just needs topping up"
                // branch below (2026-07-19: a kit slot whose ITEM IS a shulker box itself
                // never got restocked past a partial stack, since this used to `continue`
                // unconditionally before reaching the topup check).
                if (isShulkerBox(playerStack)) continue;
                boolean inGrace = System.currentTimeMillis() - kitSlotClearedAtMs.getOrDefault(i, 0L) < KIT_SLOT_CLEAR_GRACE_MS;
                int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                if (containerSlot != -1) {
                    int emptySlot = inGrace ? findEmptyUnassignedHandlerSlot(handler, containerSize) : -1;
                    if (emptySlot != -1 && emptySlot != playerSlot) {
                        atomicSwap(mc, handler.containerId, containerSlot, emptySlot);
                    } else {
                        // No redirect target (or not in grace) -- fill the designated slot
                        // directly. Waiting out the grace window here would only stall a
                        // wanted refill for no benefit (2026-07-18, "cho item vào ender chest
                        // mà đợi 1 lúc mới lấy" -- there was nowhere to redirect to, so it
                        // just sat idle until the grace timer ran out on its own).
                        atomicSwap(mc, handler.containerId, containerSlot, playerSlot);
                    }
                    return true;
                }
            }
            else if (playerStack.getCount() < playerStack.getMaxStackSize()) {
                int exactSlot = findExactItemInContainer(handler, containerSize, playerStack);
                if (exactSlot != -1) {
                    atomicSwap(mc, handler.containerId, exactSlot, playerSlot);
                    return true;
                }

                int bestSlot = findBestItemInContainer(handler, containerSize, kit);
                if (bestSlot != -1) {
                    ItemStack containerStack = handler.getSlot(bestSlot).getItem();
                    if (containerStack.getCount() > playerStack.getCount()) {
                        atomicSwap(mc, handler.containerId, bestSlot, playerSlot);
                        return true;
                    }
                }
            }
        }
        for (int i = 0; i < 36; i++) {
            KitItem kit = activeKit.get(i);
            if (kit == null) continue;

            int playerSlot = getPlayerHandlerSlot(containerSize, i);
            ItemStack playerStack = handler.getSlot(playerSlot).getItem();

            if (isShulkerBox(playerStack)) {
                if (!isItemCompensated(handler, containerSize, kit)) {
                    int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                    int emptySlot = findEmptyUnassignedSlot(handler, containerSize);
                    if (containerSlot != -1 && emptySlot != -1) {
                        atomicSwap(mc, handler.containerId, containerSlot, emptySlot);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void atomicSwap(Minecraft mc, int syncId, int containerSlot, int playerSlot) {
        click(mc, syncId, containerSlot, 0, ContainerInput.PICKUP);
        click(mc, syncId, playerSlot, 0, ContainerInput.PICKUP);
        click(mc, syncId, containerSlot, 0, ContainerInput.PICKUP);
    }

    private void click(Minecraft mc, int syncId, int slotId, int button, ContainerInput type) {
        lastContainerActionMs = System.currentTimeMillis();
        mc.gameMode.handleContainerInput(syncId, slotId, button, type, mc.player);
    }

    private boolean isCorrectItem(ItemStack stack, KitItem kit) {
        if (stack.isEmpty()) return false;
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        return stack.getItem() == expected;
    }

    private int findBestItemInContainer(AbstractContainerMenu handler, int containerSize, KitItem kit) {
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        int bestSlot = -1;
        long bestScore = -1;

        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem() == expected) {
                long score = scoreCandidate(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    /**
     * Ranks candidates of the SAME item type. Stackables keep the old max-count rule.
     * Unstackables (gear) rank by: preferred enchant present (dominates) > remaining
     * durability. Weights are strictly ordered (enchant bonus > any possible durability
     * value) so a preferred-enchant item always beats a higher-durability one without it.
     */
    private long scoreCandidate(ItemStack stack) {
        if (stack.getMaxStackSize() > 1) return stack.getCount();
        long score = 0;
        String prefEnchant = preferredEnchantId(stack);
        if (prefEnchant != null && hasEnchant(stack, prefEnchant)) score += 1_000_000L;
        if (stack.isDamageableItem()) score += stack.getMaxDamage() - stack.getDamageValue();
        return score;
    }

    /** Enchant id preferred for this stack's gear category, or null if no preference applies. */
    private String preferredEnchantId(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (id.endsWith("_pickaxe"))
            return pickaxePref.getValue() == PickaxePref.SilkTouch ? "silk_touch" : "efficiency";
        ArmorPref pref;
        if (id.endsWith("_helmet") || id.equals("turtle_helmet")) pref = helmetPref.getValue();
        else if (id.endsWith("_chestplate")) pref = chestplatePref.getValue();
        else if (id.endsWith("_leggings")) pref = leggingsPref.getValue();
        else if (id.endsWith("_boots")) pref = bootsPref.getValue();
        else return null;
        return pref == ArmorPref.Blast ? "blast_protection" : "protection";
    }

    private boolean hasEnchant(ItemStack stack, String enchantPath) {
        net.minecraft.world.item.enchantment.ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants == null || enchants.isEmpty()) return false;
        Identifier id = Identifier.parse("minecraft:" + enchantPath);
        for (var holder : enchants.keySet()) {
            if (holder.is(id)) return true;
        }
        return false;
    }

    private int findExactItemInContainer(AbstractContainerMenu handler, int containerSize, ItemStack targetStack) {
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, targetStack)) return i;
        }
        return -1;
    }

    private int findEmptyContainerSlot(AbstractContainerMenu handler, int containerSize) {
        for (int i = 0; i < containerSize; i++) {
            if (handler.getSlot(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    private int getPlayerHandlerSlot(int containerSize, int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return containerSize + 27 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return containerSize + (invSlot - 9);
        return -1;
    }

    private int getHandlerSlotPlayerOnly(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return invSlot;
        return -1;
    }

    private static String normalizeName(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).trim();
    }

    private boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isItemCompensated(AbstractContainerMenu handler, int containerSize, KitItem kit) {
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        for (int i = 0; i < 36; i++) {
            if (activeKit.get(i) == null) {
                int slot = getPlayerHandlerSlot(containerSize, i);
                if (handler.getSlot(slot).getItem().getItem() == expected) return true;
            }
        }
        return false;
    }

    private int findEmptyUnassignedSlot(AbstractContainerMenu handler, int containerSize) {
        for (int i = 0; i < 36; i++) {
            if (activeKit.get(i) == null) {
                int slot = getPlayerHandlerSlot(containerSize, i);
                if (handler.getSlot(slot).getItem().isEmpty()) return slot;
            }
        }
        return -1;
    }
}
