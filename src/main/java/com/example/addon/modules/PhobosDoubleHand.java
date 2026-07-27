package com.example.addon.modules;

import com.example.addon.modules.bedaura.DamageUtils;
import com.example.addon.util.ServerGate;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Faithful port of Gurtex-Phobos's DoubleHand, recovered from the decompiled crack jar
 * (org.phobos.secure...u87M8d19LxWmkG3N.lambda$onEvent$2 = the tick; j26wIOe49TylGTb3 /
 * 7wCPlBh1KmImWh55 / ufAQGHDakR2pvTi0 = the Legit danger triggers). "DoubleHand" silently holds a
 * totem in the selected hotbar slot so it pops without a visible swap -- the hold IS the module,
 * always silent (Phobos uses SilentHotbar; here InvHelper silent swap).
 *
 * Rage (default): auto-hold when Phobos's real danger formula fires --
 *   (hp < Health && (hp < lastHealth || |hp - lastHealth| >= Damage)) || popped within Totem ms.
 * Legit: hold while dangerTimer is live (Timeout ms), reset by a predicted-lethal incoming crystal
 *   (ClientboundAddEntity END_CRYSTAL) or a charged respawn-anchor click outside the Overworld.
 *   isLethal == predicted damage >= (health+absorption) - Threshold. Damage is estimated with the
 *   addon's DamageUtils (Boze has no DamageCalculator) -- crystal radius 6, anchor radius 5.
 *
 * Not ported here (scope, all portable -- NOT infra blockers): SilentHotbar's int arg is just
 * ticksUtilReset (auto-revert after N ticks); the per-tick hold/release here is equivalent. Rage's
 * "put a totem (or gapple while eating) into the offhand when the totem slot is your selected slot"
 * double-swap is a plain SWAP-button-40 click, same as AutoTotem's offhand fill.
 */
public class PhobosDoubleHand extends AddonModule {
    public static final PhobosDoubleHand INSTANCE = new PhobosDoubleHand();

    public enum Mode { Rage, Legit }

    @Override
    public boolean isVisible() {
        return super.isVisible() && ServerGate.isKingMC();
    }

    @Override
    public void onEnable() {
        if (!ServerGate.isKingMC()) setState(false);
    }

    public final ModeOption<Mode> mode = new ModeOption<>(this, "Mode",
            "Rage: hold on low health / a big hit / a recent pop. Legit: hold only when an incoming "
            + "crystal or anchor is predicted lethal.", Mode.Rage);
    public final SliderOption slot = new SliderOption(this, "Slot",
            "Hotbar slot (0-8) that holds the totem.", 1.0, 0.0, 8.0, 1.0);
    public final ToggleOption auto = new ToggleOption(this, "Auto",
            "Rage: enable the health/damage/pop danger hold.", true);
    public final SliderOption health = new SliderOption(this, "Health",
            "Rage: hold when health+absorption drops below this.", 10.0, 0.0, 36.0, 1.0);
    public final SliderOption damage = new SliderOption(this, "Damage",
            "Rage: hold after a single hit of at least this much.", 10.0, 1.0, 20.0, 0.5);
    public final SliderOption graceMs = new SliderOption(this, "Totem",
            "Rage: keep holding this many ms after a totem pop.", 100.0, 0.0, 1000.0, 50.0);
    public final SliderOption threshold = new SliderOption(this, "Threshold",
            "Legit: lethal margin -- danger when predicted damage >= (health+absorption) - this.", 2.0, 0.0, 6.0, 0.5);
    public final SliderOption timeout = new SliderOption(this, "Timeout",
            "Legit: ms to keep holding after a lethal crystal/anchor is seen.", 750.0, 50.0, 3000.0, 50.0);
    public final ToggleOption anchor = new ToggleOption(this, "Anchor",
            "Legit: also arm on a charged respawn-anchor click outside the Overworld.", true);
    public final ToggleOption offhandFill = new ToggleOption(this, "Offhand",
            "Legit: move a totem into an empty offhand while safe.", false);
    public final ToggleOption debug = new ToggleOption(this, "Debug", "Log hold/release actions.", false);

    private float lastHealth = 20f;
    private long lastPopMs;
    private long dangerUntilMs;
    private boolean silentHeld;

    public PhobosDoubleHand() {
        super("PhobosDoubleHand", "Silently holds a totem slot on danger -- faithful Phobos port (Rage/Legit).");
    }

    private void dbg(String msg) {
        if (debug.getValue()) ChatHelper.sendMsg("PhobosDoubleHand", msg);
    }

    private int hotbarSlot() {
        return slot.getValue().intValue();
    }

    private boolean isTotem(Minecraft mc, int hotbarIdx) {
        var s = mc.player.getInventory().getItem(hotbarIdx);
        return !s.isEmpty() && s.getItem() == Items.TOTEM_OF_UNDYING;
    }

    private void swapOffhand(Minecraft mc, int invIndex) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,
                invIndex <= 8 ? 36 + invIndex : invIndex, 40, ContainerInput.SWAP, mc.player);
    }

    private boolean isLethal(Minecraft mc, float dmg) {
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        return dmg >= hp - threshold.getValue().floatValue();
    }

    @Override
    public void onDisable() {
        if (silentHeld) { InvHelper.swapBack(); silentHeld = false; }
        dangerUntilMs = 0;
    }

    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Totem pop -> Rage recent-pop window.
        if (event.packet instanceof ClientboundEntityEventPacket p) {
            if (p.getEventId() == 35 && p.getEntity(mc.level) == mc.player) lastPopMs = System.currentTimeMillis();
            return;
        }

        // Legit: incoming crystal spawn -> predict lethal (ufAQGHDakR2pvTi0).
        if (mode.getValue() != Mode.Legit) return;
        if (event.packet instanceof ClientboundAddEntityPacket p && p.getType() == EntityType.END_CRYSTAL) {
            Vec3 center = new Vec3(p.getX(), p.getY(), p.getZ());
            float dmg = DamageUtils.estimateHpLoss(center, mc.player, 0, DamageUtils.CRYSTAL_EXPLOSION_RADIUS, true, false);
            if (isLethal(mc, dmg)) {
                dangerUntilMs = System.currentTimeMillis() + timeout.getValue().longValue();
                dbg("§dcrystal " + String.format("%.1f", dmg) + " -> danger " + timeout.getValue().intValue() + "ms");
            }
        }
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (!getState()) return;
        if (!ServerGate.isKingMC()) { setState(false); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        long now = System.currentTimeMillis();
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean danger;

        if (mode.getValue() == Mode.Rage) {
            boolean hit = hp < lastHealth || Math.abs(hp - lastHealth) >= damage.getValue().floatValue();
            danger = auto.getValue()
                    && ((hp < health.getValue() && hit) || now - lastPopMs < graceMs.getValue().longValue());
        } else {
            if (anchor.getValue() && mc.options.keyUse.isDown() && mc.level.dimension() != Level.OVERWORLD
                    && mc.hitResult instanceof BlockHitResult bhr) {
                var state = mc.level.getBlockState(bhr.getBlockPos());
                if (state.getBlock() instanceof RespawnAnchorBlock && state.getValue(RespawnAnchorBlock.CHARGE) > 0) {
                    float dmg = DamageUtils.estimateHpLoss(bhr.getBlockPos().getCenter(), mc.player, 0, 5.0f, true, false);
                    if (isLethal(mc, dmg)) dangerUntilMs = now + timeout.getValue().longValue();
                }
            }
            danger = now < dangerUntilMs;
        }
        lastHealth = hp;

        int target = hotbarSlot();
        boolean hold = danger && isTotem(mc, target);

        if (hold && !silentHeld) {
            if (InvHelper.swapToSlot(target, SwapType.Silent)) {
                silentHeld = true;
                dbg("§esilent hold slot " + target);
            }
        } else if (!hold && silentHeld) {
            InvHelper.swapBack();
            silentHeld = false;
            dbg("§7release");
        }

        // Rage double-hand: when the totem slot IS your real selected slot, also keep a totem (or a
        // gapple while eating) in the offhand -- the "double" in DoubleHand (u87M8d19LxWmkG3N Rage).
        if (mode.getValue() == Mode.Rage && !silentHeld && mc.player.containerMenu.containerId == 0
                && target == mc.player.getInventory().getSelectedSlot()) {
            boolean eating = mc.options.keyUse.isDown();
            ItemStack off = mc.player.getOffhandItem();
            boolean offGood = eating
                    ? (off.getItem() == Items.GOLDEN_APPLE || off.getItem() == Items.ENCHANTED_GOLDEN_APPLE)
                    : off.getItem() == Items.TOTEM_OF_UNDYING;
            if (!offGood) {
                int src = eating ? InvHelper.find(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE)
                                 : InvHelper.find(Items.TOTEM_OF_UNDYING);
                if (src >= 0) {
                    swapOffhand(mc, src);
                    dbg("§adouble-hand offhand <- " + (eating ? "gapple" : "totem") + " (slot " + src + ")");
                }
            }
        }

        // Legit: top up an empty offhand with a totem while calm (u87M8d19LxWmkG3N Legit branch).
        if (mode.getValue() == Mode.Legit && offhandFill.getValue() && !danger && !silentHeld
                && mc.player.getOffhandItem().isEmpty() && mc.player.containerMenu.containerId == 0) {
            int src = InvHelper.find(Items.TOTEM_OF_UNDYING);
            if (src >= 0) {
                swapOffhand(mc, src);
                dbg("§boffhand <- totem (slot " + src + ")");
            }
        }
    }
}
