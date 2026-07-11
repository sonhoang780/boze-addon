package com.example.addon.modules.betterrekit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
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
    public final ModeOption<SwapType> swapMode = new ModeOption<>(this, "SwapMode",
        "Swap type used when placing shulkers and swapping to a breaking tool in Auto mode.", SwapType.Alt);

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
    private int autoTicks = 0;
    private int emptyTicks = 0;
    private int shulkerEnderSlot = -1;       // slot in ender chest holding the shulker
    private BlockPos placedShulkerPos = null; // world position of placed shulker
    private BlockPos enderChestPos = null;    // position of the ender chest block
    private boolean breakingStarted = false;
    private boolean toolSwappedForBreak = false;

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
        toolSwappedForBreak = false;
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;

        if (auto.getValue()) {
            handleAutoTick(mc);
            return;
        }

        // ── ORIGINAL MANUAL MODE ──
        if (!(mc.screen instanceof AbstractContainerScreen)) return;
        if (mc.screen instanceof InventoryScreen) return;
        manualPullTick(mc);
    }

    /** One manual-mode pull pass (delay + actionsPerTick respected). Shared by manual mode
     *  and by Auto's IDLE state when the player opens a non-ender-chest container by hand. */
    private void manualPullTick(Minecraft mc) {
        if (activeKit.isEmpty()) return;
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
                autoState = AutoState.IDLE;
                return;
            }
            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize <= 0) return;
            shulkerEnderSlot = findShulkerInContainer(mc, containerSize);
            if (shulkerEnderSlot == -1) {
                autoState = AutoState.IDLE;
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

            // find empty unassigned inventory slot
            int emptyInvSlot = findEmptyUnassignedHandlerSlot(handler, containerSize);
            if (emptyInvSlot == -1) {
                autoState = AutoState.IDLE;
                return;
            }

            // atomic swap: shulker from ender chest → player inventory
            atomicSwap(mc, handler.containerId, shulkerEnderSlot, emptyInvSlot);
            autoState = AutoState.PLACE_SHULKER;
            autoTicks = 0;
            return;
        }

        // PLACE_SHULKER: close ender chest screen, place shulker on ground
        if (autoState == AutoState.PLACE_SHULKER) {
            if (screenOpen) {
                mc.setScreen(null);
                return; // wait next tick after closing
            }

            // wait a tick for screen close to propagate
            if (autoTicks < 1) { autoTicks++; return; }

            // find the shulker in inventory
            int shulkerInvSlot = findShulkerInInventory(mc);
            if (shulkerInvSlot == -1) {
                autoState = AutoState.IDLE;
                return;
            }

            // find placeable spot near player
            BlockPos placePos = findPlaceableSpot(mc);
            if (placePos == null) {
                autoState = AutoState.RETURN_SHULKER; // try to return it
                return;
            }

            // swap shulker to hand and place
            BlockHitResult hit = getHitResultForPlace(mc, placePos);
            if (hit == null) {
                autoState = AutoState.RETURN_SHULKER;
                return;
            }

            // Placing a block needs the shulker genuinely in the local main hand -- Alt/Silent
            // swap types are illusion-swaps (fool the server/observers, don't reliably change
            // what PlaceHelper actually sees locally), which is why the shulker never landed
            // and OPEN_SHULKER kept timing out with "Could not open placed shulker". Placement
            // always uses a real (Normal) swap regardless of the configured SwapMode.
            // Normal swap = Inventory.setSelectedSlot, which CRASHES on slots >8 ("Invalid
            // selected slot") -- move the shulker into the hotbar first if it isn't there.
            shulkerInvSlot = ensureHotbar(mc, shulkerInvSlot);
            InvHelper.swapToSlot(shulkerInvSlot, SwapType.Normal);
            PlaceHelper.place(InteractionMode.NCP, hit, InteractionHand.MAIN_HAND);
            // swapBack deferred to OPEN_SHULKER — hand must be empty to right-click shulker

            placedShulkerPos = placePos;
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
                return;
            }

            // wait for block placement to settle before first attempt
            if (autoTicks < 3) { autoTicks++; return; }

            // retry useItemOn every 4 ticks (5 attempts per second)
            if (autoTicks % 4 == 0) {
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
                toolSwappedForBreak = false;
            }
            return;
        }

        // BREAK_SHULKER: close screen, break the placed shulker block
        if (autoState == AutoState.BREAK_SHULKER) {
            if (screenOpen) {
                mc.setScreen(null);
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
                if (toolSwappedForBreak) { InvHelper.swapBack(); toolSwappedForBreak = false; }
                autoState = AutoState.RETURN_SHULKER;
                autoTicks = 0;
                return;
            }

            // timeout: 200 ticks (10 seconds) for breaking
            if (autoTicks > 200) {
                if (toolSwappedForBreak) { InvHelper.swapBack(); toolSwappedForBreak = false; }
                autoState = AutoState.RETURN_SHULKER; // try to return (shulker still placed)
                autoTicks = 0;
                return;
            }

            if (!breakingStarted) {
                // Shulker boxes have no requiresCorrectToolForDrops() (see
                // Blocks#shulkerBoxProperties) -- any tool, including bare hand, always
                // drops the shulker with contents intact. Swapping to a pickaxe here is
                // purely a speed nicety, not a safety requirement; skip it if none found.
                var shulkerState = mc.level.getBlockState(placedShulkerPos);
                if (!mc.player.getMainHandItem().isCorrectToolForDrops(shulkerState)) {
                    int toolSlot = findBreakingTool(shulkerState);
                    if (toolSlot != -1) {
                        // Normal swap only accepts hotbar slots (setSelectedSlot crashes on >8).
                        if (swapMode.getValue() == SwapType.Normal) toolSlot = ensureHotbar(mc, toolSlot);
                        toolSwappedForBreak = InvHelper.swapToSlot(toolSlot, swapMode.getValue());
                    }
                }
                mc.gameMode.startDestroyBlock(placedShulkerPos, Direction.UP);
                breakingStarted = true;
            }
            mc.gameMode.continueDestroyBlock(placedShulkerPos, Direction.UP);
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
                    toolSwappedForBreak = false;
                    return;
                }
                autoState = AutoState.FIND_SHULKER;
                autoTicks = 0;
                return;
            }

            int containerSize = mc.player.containerMenu.slots.size() - 36;
            if (containerSize <= 0) return;
            AbstractContainerMenu handler = mc.player.containerMenu;

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

            placedShulkerPos = null;
            shulkerEnderSlot = -1;
            if (isKitComplete(mc)) {
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

    // Vanilla's own tool-correctness check against the actual shulker block state
    // (isCorrectToolForDrops) instead of guessing by item id suffix -- shulker boxes
    // require minecraft:mineable/pickaxe specifically; an axe breaks them but drops
    // nothing.
    private int findBreakingTool(net.minecraft.world.level.block.state.BlockState shulkerState) {
        return InvHelper.find(stack -> !stack.isEmpty() && stack.isCorrectToolForDrops(shulkerState));
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

    private BlockPos findPlaceableSpot(Minecraft mc) {
        BlockPos base = mc.player.blockPosition();
        // search in a small radius around the player's feet
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos candidate = base.offset(dx, 0, dz);
                // need air at candidate AND solid block below
                if (mc.level.getBlockState(candidate).canBeReplaced()
                    && !mc.level.getBlockState(candidate.below()).canBeReplaced()
                    && !mc.level.getBlockState(candidate.below()).is(Blocks.AIR)) {
                    // also need air above for shulker to open (shulker is 1 block tall)
                    if (mc.level.getBlockState(candidate.above()).canBeReplaced()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
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
            // ponytail: dirty cursor blocks all pulling — clear it into container or empty player slot
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

        for (int i = 0; i < 36; i++) {
            KitItem kit = activeKit.get(i);
            if (kit == null) continue;

            int playerSlot = getPlayerHandlerSlot(containerSize, i);
            ItemStack playerStack = handler.getSlot(playerSlot).getItem();
            if (isShulkerBox(playerStack)) continue;
            if (!isCorrectItem(playerStack, kit)) {
                int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                if (containerSlot != -1) {
                    atomicSwap(mc, handler.containerId, containerSlot, playerSlot);
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
