package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

/**
 * FakeFly — ControlRocket-style Grim-compatible elytra flight.
 *
 * How direction control works (critical):
 *   EventTick.Pre  → compute WASD direction → player.setYaw(targetYaw).
 *                    The player tick runs after Pre and calls sendMovementPackets()
 *                    which reads player.yaw at that moment → move packet carries
 *                    targetYaw → server updates player rotation.
 *                    We also set player.bodyYaw = targetYaw for the visual body turn.
 *                    Camera restore is saved for Post.
 *   EventTick.Post → fire the rocket. Server already received the move packet with
 *                    targetYaw this tick → uses targetYaw for boost direction → correct.
 *                    Restore player.setYaw(cameraYaw) here, BEFORE the frame renders
 *                    (render happens after Post in Minecraft's game loop) → no camera snap.
 *
 * EventRotate is NOT used — it is an internal Boze interaction-system hook, not
 * a general move-packet hook, and does not reliably fire for addon-level code.
 */
public class FakeFly extends AddonModule {
    public static final FakeFly INSTANCE = new FakeFly();

    /** When true, InvMovePlus must NOT defer ClickSlotC2SPackets. */
    public static volatile boolean invMoveBypass = false;

    public enum FlySwapMode { Alt, Silent, Normal }
    public enum TryHoldMode { Off, On }

    public final SliderOption upSpeed = new SliderOption(this, "UpSpeed",
            "Upward pitch fraction with horizontal + Space (0=level, 1=straight up).", 0.4, 0.0, 1.0, 0.05);
    public final SliderOption downSpeed = new SliderOption(this, "DownSpeed",
            "Downward pitch fraction with horizontal + Shift (0=level, 1=straight down).", 0.7, 0.0, 1.0, 0.05);
    public final SliderOption conserveDelay = new SliderOption(this, "ConserveDelay",
            "Ticks between rocket fires. 0 = fire every tick while a key is pressed.", 0.0, 0.0, 60.0, 1.0);
    public final ModeOption<TryHoldMode> tryHold = new ModeOption<>(this, "TryHold",
            "Off: free-fall when no keys. On: upward rocket when falling to hold altitude.", TryHoldMode.Off);
    public final ToggleOption chestplateMode = new ToggleOption(this, "ChestplateMode",
            "Swap elytra in/out each tick so server sees elytra, you wear armour.", false);
    public final ToggleOption autoTakeoff = new ToggleOption(this, "AutoTakeoff",
            "ChestplateMode only: auto-jump off ground.", false);
    public final ModeOption<FlySwapMode> swap = new ModeOption<>(this, "Swap",
            "Normal: hotbar. Silent: instant swap. Alt: full inventory.", FlySwapMode.Silent);

    // ── State ────────────────────────────────────────────────────────────────
    private int     rocketCooldown          = 0;
    private int     elytraScreenSlot        = -1;
    private boolean cpModeActive            = false;
    private boolean flying                  = false;
    private boolean equipPending            = false;
    private int     equipTick               = 0;
    private int     pendingElytraScreenSlot = -1;
    private int     equippedFromScreenSlot  = -1;
    private boolean didSwapIn               = false;
    // tracks last known value of chestplateMode to detect in-flight toggles
    private boolean lastChestplateModeValue = false;

    // Pre → Post communication for rocket firing + camera restore
    private boolean pendingFire                      = false;
    /** Camera pitch/yaw saved in Pre; exposed for MixinEntity to return to the renderer. */
    public static volatile float   savedCameraYaw   = 0f;
    public static volatile float   savedCameraPitch = 0f;
    /**
     * True from when we set targetPitch/Yaw in Pre until we restore them in Post.
     * MixinEntity intercepts entity.getPitch/getYaw during this window so the
     * renderer (and first-person arm) sees the real camera rotation, not the
     * artificial flight direction.
     */
    public static volatile boolean cameraOverrideActive = false;
    // tracks gliding state to detect fresh glide start (first rocket ignores conserveDelay)
    private boolean wasGliding          = false;

    public FakeFly() {
        super("FakeFly", "Grim-compatible ControlRocket elytra flight. WASD direction with body rotation, camera free.");
    }

    /**
     * True while the module has taken off — covers BOTH real elytra gliding and ChestplateMode
     * (where {@code isGliding()} is false). Used by MixinGameRenderer to suppress the first-person
     * view-bob (left/right hand sway) in every flight state, not only while gliding.
     */
    public boolean isFlying() { return flying; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        rocketCooldown = 0; elytraScreenSlot = -1; cpModeActive = false; flying = false;
        equipPending = false; equipTick = 0; pendingElytraScreenSlot = -1;
        equippedFromScreenSlot = -1; didSwapIn = false;
        pendingFire = false; invMoveBypass = false; wasGliding = false; cameraOverrideActive = false;
        lastChestplateModeValue = chestplateMode.getValue();

        boolean hasElytra = mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);

        if (chestplateMode.getValue()) {
            if (hasElytra) { flying = true; }
            else {
                int slot = findElytraScreenSlot(mc);
                if (slot == -1) { ChatHelper.sendMsg("FakeFly", "§cNo elytra found!"); return; }
                elytraScreenSlot = slot; cpModeActive = true; flying = true;
            }
        } else {
            if (hasElytra) { flying = true; }
            else {
                int slot = findElytraScreenSlot(mc);
                if (slot == -1) { ChatHelper.sendMsg("FakeFly", "§cNo elytra found!"); return; }
                pendingElytraScreenSlot = slot; equipPending = true; equipTick = 0;
            }
        }
    }

    @Override
    public void onDisable() {
        invMoveBypass = false;
        cameraOverrideActive = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Restore camera if we left it at a target rotation
        if (pendingFire) {
            mc.player.setYaw(savedCameraYaw);
            mc.player.setPitch(savedCameraPitch);
        }
        pendingFire = false;

        if (cpModeActive) {
            if (mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA) && elytraScreenSlot != -1) {
                int syncId = mc.player.playerScreenHandler.syncId;
                mc.interactionManager.clickSlot(syncId, 6,                0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(syncId, elytraScreenSlot, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(syncId, 6,                0, SlotActionType.PICKUP, mc.player);
            }
            cpModeActive = false; elytraScreenSlot = -1;
        } else if (didSwapIn && equippedFromScreenSlot != -1) {
            int syncId = mc.player.playerScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, 6,                     0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, equippedFromScreenSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, 6,                     0, SlotActionType.PICKUP, mc.player);
        }

        equippedFromScreenSlot = -1; didSwapIn = false; equipPending = false; flying = false;
    }

    // ── Phase 1 (Pre): set camera + body to target direction ─────────────────

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        pendingFire = false;
        if (mc.player == null || mc.world == null) return;

        // Late init: onEnable fired before player was ready (e.g. module pre-enabled at world load)
        if (!flying && !equipPending && !cpModeActive) {
            lateInit(mc);
            return;
        }

        // Detect chestplateMode toggle while module is running — reinitialise CP state
        boolean wantCp = chestplateMode.getValue();
        if (wantCp != lastChestplateModeValue) {
            lastChestplateModeValue = wantCp;
            cpModeActive = false; invMoveBypass = false; rocketCooldown = 0;
            if (wantCp) {
                boolean hasE = mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
                if (!hasE) {
                    int s = findElytraScreenSlot(mc);
                    if (s != -1) { elytraScreenSlot = s; cpModeActive = true; flying = true; }
                }
                // hasE=true: elytra is equipped — user must manually swap elytra→inventory
                // and equip chestplate first, then CP mode will take over
            } else {
                elytraScreenSlot = -1;
                flying = mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
            }
            return;
        }

        if (equipPending) { handleEquipSequence(mc); return; }

        if (cpModeActive) {
            doChestplateSwap(mc);
        } else if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            return;
        }

        if (!flying) return;
        if (!mc.player.isGliding() && !cpModeActive) {
            if (mc.player.isOnGround() && autoTakeoff.getValue()) mc.player.jump();
            return;
        }

        // Reset cooldown when elytra gliding starts fresh so the first rocket fires immediately
        boolean nowGliding = mc.player.isGliding();
        if (nowGliding && !wasGliding) { rocketCooldown = 0; pendingFire = true; }
        wasGliding = nowGliding;
        
        prepDirection(mc);
    }

    // Mirrors onEnable() init logic; called when onEnable fired before player entity existed.
    private void lateInit(MinecraftClient mc) {
        if (mc.player == null) return;
        lastChestplateModeValue = chestplateMode.getValue();
        boolean hasE = mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        if (chestplateMode.getValue()) {
            if (hasE) { flying = true; }
            else {
                int s = findElytraScreenSlot(mc);
                if (s != -1) { elytraScreenSlot = s; cpModeActive = true; flying = true; }
            }
        } else {
            if (hasE) { flying = true; }
            else {
                int s = findElytraScreenSlot(mc);
                if (s != -1) { pendingElytraScreenSlot = s; equipPending = true; equipTick = 0; }
            }
        }
    }

    // ── Phase 2 (Post): fire rocket, restore camera ───────────────────────────

    /**
     * sendMovementPackets() runs during the player tick, between Pre and Post.
     * By this point the server received the move packet with targetYaw (set in Pre).
     * We fire the rocket now — server uses that rotation for boost direction.
     * We then restore camera yaw — frame renders after Post so no visual snap occurs.
     */
    @EventHandler
    private void onTickPost(EventTick.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!pendingFire || mc.player == null) return;

        pendingFire = false;  // consume before anything that could throw

        try {
            if (rocketCooldown > 0) {
                rocketCooldown--;
                return;
            }

            int slot = swap.getValue() == FlySwapMode.Normal
                ? InvHelper.findInHotbar(Items.FIREWORK_ROCKET)
                : InvHelper.find(Items.FIREWORK_ROCKET);

            if (slot != -1 && mc.getNetworkHandler() != null) {
                InvHelper.swapToSlot(slot, getSwapType());
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvHelper.swapBack();
                rocketCooldown = conserveDelay.getValue().intValue();
            }
        } finally {
            // Always restore camera, even if rocket failed / no fireworks
            mc.player.setYaw(savedCameraYaw);
            mc.player.setPitch(savedCameraPitch);
            // lastYaw/lastPitch are set to targetYaw/targetPitch by the entity tick that ran
            // between Pre and Post. Reset them to the natural values so getPitch(tickDelta)
            // interpolates between identical values → first-person arms don't fling.
            mc.player.lastYaw   = savedCameraYaw;
            mc.player.lastPitch = savedCameraPitch;
            // Release the mixin override — camera is back to natural rotation.
            cameraOverrideActive = false;
        }
    }

    // ── Direction setup ───────────────────────────────────────────────────────

    private void prepDirection(MinecraftClient mc) {
        savedCameraYaw   = mc.player.getYaw();
        savedCameraPitch = mc.player.getPitch();

        double yawRad = Math.toRadians(savedCameraYaw);
        double sinYaw = Math.sin(yawRad), cosYaw = Math.cos(yawRad);

        double dx = 0, dz = 0;
        if (mc.options.forwardKey.isPressed())  { dx -= sinYaw; dz += cosYaw; }
        if (mc.options.backKey.isPressed())     { dx += sinYaw; dz -= cosYaw; }
        if (mc.options.leftKey.isPressed())     { dx += cosYaw; dz += sinYaw; }
        if (mc.options.rightKey.isPressed())    { dx -= cosYaw; dz -= sinYaw; }

        double  len   = Math.sqrt(dx * dx + dz * dz);
        boolean hasH  = len > 0.01;
        boolean space = mc.options.jumpKey.isPressed();
        boolean shift = mc.options.sneakKey.isPressed();

        float targetYaw;
        float targetPitch;
        if (hasH) {
            targetYaw = (float) Math.toDegrees(Math.atan2(-dx / len, dz / len));
            if (space)      targetPitch = -(float)(upSpeed.getValue()   * 90.0);
            else if (shift) targetPitch =  (float)(downSpeed.getValue() * 90.0);
            else {
                // Damp altitude oscillation: pitch slightly into the velocity error.
                // vy > 0 (rising) → positive pitch (aim down) → rocket counters climb.
                // vy < 0 (falling) → negative pitch (aim up) → rocket counters descent.
                // -7° bias at vy=0: sin(7°)*1.5 boost ≈ 0.18 m/tick > gravity 0.08 m/tick
                // so equilibrium settles at a slight climb instead of a slow descent.
                double vy = mc.player.getVelocity().y;
                targetPitch = (float) Math.max(-20.0, Math.min(20.0, vy * 50.0 - 7.0));
            }
        } else if (space) {
            targetYaw = savedCameraYaw; targetPitch = -90f;
        } else if (shift) {
            targetYaw = savedCameraYaw; targetPitch = 90f;
        } else if (tryHold.getValue() == TryHoldMode.On && mc.player.getVelocity().y < -0.1 && rocketCooldown <= 0) {
            targetYaw = savedCameraYaw; targetPitch = -20f;
        } else {
            return;
        }

        // Rotate player to targetYaw so sendMovementPackets() (called during the
        // player tick between Pre and Post) sends a move packet with targetYaw.
        // The server updates its player rotation → when the rocket fires in Post,
        // the server uses targetYaw for the boost direction.
        mc.player.setYaw(targetYaw);
        mc.player.setPitch(targetPitch);

        // Body faces the flight direction visually. NOT restored in Post so the
        // body keeps facing targetYaw until Minecraft's interpolation brings it
        // back toward camera yaw — matching ControlRocket's body-turn behaviour.
        mc.player.bodyYaw = targetYaw;

        // Signal MixinEntity to intercept getPitch/getYaw for this player until Post
        // restores the camera. This ensures the first-person arm renderer always sees
        // the real camera rotation even while the entity's rotation is set to targetPitch/Yaw.
        cameraOverrideActive = true;
        pendingFire = true;
    }

    // ── ChestplateMode ────────────────────────────────────────────────────────

    private void doChestplateSwap(MinecraftClient mc) {
        if (mc.currentScreen != null || elytraScreenSlot == -1) return;

        if (mc.player.isOnGround()) {
            if (autoTakeoff.getValue()) mc.player.jump();
            return;
        }

        if (!mc.player.playerScreenHandler.getSlot(elytraScreenSlot).getStack().isOf(Items.ELYTRA)) {
            int newSlot = findElytraScreenSlot(mc);
            if (newSlot == -1) {
                ChatHelper.sendMsg("FakeFly", "§cElytra lost!");
                cpModeActive = false; flying = false; return;
            }
            elytraScreenSlot = newSlot;
        }

        int syncId = mc.player.playerScreenHandler.syncId;
        invMoveBypass = true;
        try {
            mc.interactionManager.clickSlot(syncId, elytraScreenSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, 6,                0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, elytraScreenSlot, 0, SlotActionType.PICKUP, mc.player);

            if (mc.getNetworkHandler() != null)
                mc.getNetworkHandler().sendPacket(
                    new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));

            mc.interactionManager.clickSlot(syncId, 6,                0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, elytraScreenSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, 6,                0, SlotActionType.PICKUP, mc.player);
        } finally {
            invMoveBypass = false;
        }
    }

    // ── Normal-mode equip sequence ─────────────────────────────────────────────

    private void handleEquipSequence(MinecraftClient mc) {
        if (mc.currentScreen != null) return;
        equipTick++;
        if (equipTick == 1) {
            int syncId = mc.player.playerScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, pendingElytraScreenSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, 6,                       0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, pendingElytraScreenSlot, 0, SlotActionType.PICKUP, mc.player);
            return;
        }
        equipPending = false;
        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            ChatHelper.sendMsg("FakeFly", "§cFailed to equip elytra."); return;
        }
        equippedFromScreenSlot  = pendingElytraScreenSlot;
        didSwapIn               = true;
        pendingElytraScreenSlot = -1;
        flying                  = true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int findElytraScreenSlot(MinecraftClient mc) {
        for (int i = 9; i <= 44; i++) {
            if (mc.player.playerScreenHandler.getSlot(i).getStack().isOf(Items.ELYTRA)) return i;
        }
        return -1;
    }

    private SwapType getSwapType() {
        return switch (swap.getValue()) {
            case Normal -> SwapType.Normal;
            case Silent -> SwapType.Silent;
            default     -> SwapType.Alt;
        };
    }
}
