package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import com.example.addon.util.ServerGate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Faithful port of Gurtex-Phobos's AutoTotem, recovered from the decompiled crack jar
 * (org.phobos.secure...ZG5skjdTXgUnoefX = the tick listener; the deobf shell
 * fourkay/trey/deobf/.../AutoTotem.java = setItem()/settings). This is the real offhand-fill
 * chooser (ZG5skjdTXgUnoefX.lambda$onEvent$3), NOT the active-pop invention the earlier version had
 * -- Phobos never calls useItem(); it just keeps the right item in the offhand and lets vanilla pop.
 *
 * Real chooser priority (health = health + absorption; actionActive = Action && right-mouse held &&
 * no screen && main-hand weapon is an allowed ActionType):
 *   health <= (actionActive ? ActionHealth : Health) -> TOTEM
 *   else actionActive                                -> GOLDEN_APPLE
 *   else                                             -> (totemCount==0 ? Fallback : Offhand) item
 * Then setItem() swaps it in via SWAP button 40 (Legit only differs by needing the inventory open).
 *
 * Not ported here (scope, all portable -- NOT infra blockers): the Shield keybind branch (put a
 * shield in offhand while a bound key is held -- Boze has BindOption/Bind + the addon's
 * isBindDown() helper), Macro mode's external keybind trigger (BindOption), and the Auto
 * open/close-inventory sequencing (mc.setScreen(InventoryScreen) + ServerboundContainerClosePacket).
 * Recent-pop guard (checkTimer 750ms) IS ported via the totem-pop entity event.
 */
public class PhobosAutoTotem extends AddonModule {
    public static final PhobosAutoTotem INSTANCE = new PhobosAutoTotem();

    private static final int OFFHAND_SWAP_BUTTON = 40;

    @Override
    public boolean isVisible() {
        return super.isVisible() && ServerGate.isKingMC();
    }

    @Override
    public void onEnable() {
        if (!ServerGate.isKingMC()) setState(false);
    }

    public enum Mode { Normal, Legit, Macro }
    public enum OffhandItem { None, Totem, Crystal }
    public enum FallbackItem { None, Totem, Crystal, GoldenApple, EnchantedGoldenApple }

    public final ModeOption<Mode> mode = new ModeOption<>(this, "Mode",
            "Normal: atomic container SWAP click. Legit: same SWAP but only while the inventory "
            + "screen is open. Macro: no in-addon equivalent (Phobos triggers it off an external "
            + "keybind) -- treated as Normal.", Mode.Normal);
    public final SliderOption delay = new SliderOption(this, "Delay",
            "Ticks to wait between offhand swaps.", 0.0, 0.0, 10.0, 1.0);

    public final ModeOption<OffhandItem> offhand = new ModeOption<>(this, "Offhand",
            "Item to keep in the offhand while safe (Phobos default: None -- the low-health totem "
            + "fill below is the module's main job; this is for holding crystals/etc when calm).",
            OffhandItem.None);
    public final ModeOption<FallbackItem> fallback = new ModeOption<>(this, "Fallback",
            "Item to hold in the offhand once you're OUT of totems.", FallbackItem.Crystal);
    public final SliderOption health = new SliderOption(this, "Health",
            "Put a totem in the offhand while health+absorption is at or below this.", 14.0, 0.0, 36.0, 1.0);

    public final ToggleOption action = new ToggleOption(this, "Action",
            "While actively fighting (right-mouse held with an allowed weapon), lower the totem "
            + "threshold to ActionHealth and hold a golden apple otherwise.", true);
    public final ToggleOption actionSword = new ToggleOption(this, "ActionSword", "Allow Action while holding a sword.", true);
    public final ToggleOption actionPickaxe = new ToggleOption(this, "ActionPickaxe", "Allow Action while holding a pickaxe.", false);
    public final ToggleOption actionAxe = new ToggleOption(this, "ActionAxe", "Allow Action while holding an axe.", false);
    public final ToggleOption actionMace = new ToggleOption(this, "ActionMace", "Allow Action while holding a mace.", false);
    public final SliderOption actionHealth = new SliderOption(this, "ActionHealth",
            "Totem threshold while Action is active.", 5.0, 0.1, 36.0, 0.1);

    public final SliderOption timeout = new SliderOption(this, "Timeout",
            "Milliseconds to wait after a swap before the next one can trigger.", 500.0, 0.0, 2000.0, 50.0);
    public final ToggleOption auto = new ToggleOption(this, "Auto",
            "Legit: auto-open the inventory to perform the swap, then close it (Phobos legit+auto).", true);
    public final SliderOption closeMs = new SliderOption(this, "Close",
            "Legit+Auto: milliseconds to keep the inventory open after swapping.", 100.0, 5.0, 500.0, 5.0);
    public final ToggleOption debug = new ToggleOption(this, "Debug", "Log swap actions.", false);

    private int nextActionTick;
    private long noSwapUntilMs; // recent-pop guard (Phobos checkTimer, 750ms)
    private long closeScreenAtMs;
    private boolean autoOpenedScreen;

    public PhobosAutoTotem() {
        super("PhobosAutoTotem", "Keeps the right item in the offhand (totem on low HP, gapple while fighting) -- faithful Phobos port.");
    }

    private void dbg(String msg) {
        if (debug.getValue()) ChatHelper.sendMsg("PhobosAutoTotem", msg);
    }

    private Item offhandItem() {
        return switch (offhand.getValue()) {
            case None -> null;
            case Totem -> Items.TOTEM_OF_UNDYING;
            case Crystal -> Items.END_CRYSTAL;
        };
    }

    private Item fallbackItem() {
        return switch (fallback.getValue()) {
            case None -> null;
            case Totem -> Items.TOTEM_OF_UNDYING;
            case Crystal -> Items.END_CRYSTAL;
            case GoldenApple -> Items.GOLDEN_APPLE;
            case EnchantedGoldenApple -> Items.ENCHANTED_GOLDEN_APPLE;
        };
    }

    private int menuSlot(int invIndex) {
        return invIndex >= 0 && invIndex <= 8 ? 36 + invIndex : invIndex;
    }

    private void click(Minecraft mc, int slotId, int button, ContainerInput type) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotId, button, type, mc.player);
    }

    private int findItem(Minecraft mc, Item item, int start, int end) {
        Inventory inv = mc.player.getInventory();
        for (int i = start; i <= end; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }

    private int countItem(Minecraft mc, Item item) {
        Inventory inv = mc.player.getInventory();
        int n = 0;
        for (int i = 0; i <= 35; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) n += s.getCount();
        }
        return n;
    }

    private boolean onCooldown(Minecraft mc) {
        return mc.player.tickCount < nextActionTick;
    }

    private void arm(Minecraft mc) {
        nextActionTick = mc.player.tickCount + Math.round((timeout.getValue().floatValue()
                + delay.getValue().floatValue() * 50f) / 50f);
    }

    private boolean swapIntoOffhand(Minecraft mc, int srcIndex) {
        // Phobos: every mode swaps the offhand via SWAP button 40. Legit needs the inventory open;
        // with Auto, open it here and schedule the close (Phobos legit+auto open/close sequence).
        if (mode.getValue() == Mode.Legit && !(mc.screen instanceof InventoryScreen)) {
            if (!auto.getValue()) return false;
            mc.setScreen(new InventoryScreen(mc.player));
            autoOpenedScreen = true;
            closeScreenAtMs = System.currentTimeMillis() + closeMs.getValue().longValue();
        }
        click(mc, menuSlot(srcIndex), OFFHAND_SWAP_BUTTON, ContainerInput.SWAP);
        return true;
    }

    private boolean actionActive(Minecraft mc) {
        if (!action.getValue() || mc.screen != null || !mc.options.keyUse.isDown()) return false;
        ItemStack main = mc.player.getMainHandItem();
        if (main.isEmpty()) return false;
        if (actionSword.getValue() && main.is(ItemTags.SWORDS)) return true;
        if (actionPickaxe.getValue() && main.is(ItemTags.PICKAXES)) return true;
        if (actionAxe.getValue() && main.is(ItemTags.AXES)) return true;
        if (actionMace.getValue() && main.getItem() == Items.MACE) return true;
        return false;
    }

    /** Recent totem pop -> Phobos checkTimer: hold off swaps 750ms so the fresh pop settles. */
    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != 35) return; // PROTECTED_FROM_DEATH (totem pop)
        if (p.getEntity(mc.level) != mc.player) return;
        noSwapUntilMs = System.currentTimeMillis() + 750L;
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (!getState()) return;
        if (!ServerGate.isKingMC()) { setState(false); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // Auto: close the inventory we opened once its Close delay elapses (runs regardless of cooldown).
        if (autoOpenedScreen && System.currentTimeMillis() >= closeScreenAtMs) {
            if (mc.screen instanceof InventoryScreen) mc.player.closeContainer();
            autoOpenedScreen = false;
        }

        if (mc.player.containerMenu.containerId != 0 && mode.getValue() != Mode.Legit) return;
        if (onCooldown(mc) || System.currentTimeMillis() < noSwapUntilMs) return;

        boolean fighting = actionActive(mc);
        double eff = mc.player.getHealth() + mc.player.getAbsorptionAmount();

        // Real Phobos chooser (ZG5skjdTXgUnoefX.lambda$onEvent$3), minus the Shield-keybind branch.
        Item target;
        if (eff <= (fighting ? actionHealth.getValue() : health.getValue())) {
            target = Items.TOTEM_OF_UNDYING;
        } else if (fighting) {
            target = Items.GOLDEN_APPLE;
        } else {
            target = countItem(mc, Items.TOTEM_OF_UNDYING) == 0 ? fallbackItem() : offhandItem();
        }
        if (target == null) return;

        ItemStack off = mc.player.getOffhandItem();
        if (!off.isEmpty() && off.getItem() == target) return;

        int src = findItem(mc, target, 0, 35);
        if (src < 0) return;
        if (swapIntoOffhand(mc, src)) {
            arm(mc);
            dbg("§boffhand <- " + target + " (slot " + src + ", hp " + (int) eff + (fighting ? ", fighting)" : ")"));
        }
    }
}
