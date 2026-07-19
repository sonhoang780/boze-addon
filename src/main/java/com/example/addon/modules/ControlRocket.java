package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;

/**
 * ControlRocket — Grim-compatible elytra flight.
 *
 * How direction control works (critical):
 *   EventTick.Pre  → compute WASD direction → player.setYRot(targetYaw).
 *                    The player tick runs after Pre and calls sendMovementPackets()
 *                    which reads player.yaw at that moment → move packet carries
 *                    targetYaw → server updates player rotation.
 *                    We also set player.setYBodyRot(targetYaw) for the visual body turn.
 *                    Camera restore is saved for Post.
 *   EventTick.Post → fire the rocket. Server already received the move packet with
 *                    targetYaw this tick → uses targetYaw for boost direction → correct.
 *                    Restore player.setYRot(cameraYaw) here, BEFORE the frame renders
 *                    (render happens after Post in Minecraft's game loop) → no camera snap.
 *
 * EventRotate is NOT used — it is an internal Boze interaction-system hook, not
 * a general move-packet hook, and does not reliably fire for addon-level code.
 */
public class ControlRocket extends AddonModule {
    public static final ControlRocket INSTANCE = new ControlRocket();

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
    public final ToggleOption fakeFlyMode = new ToggleOption(this, "FakeFly",
            "Constant chestplate swap", false);
    public final ToggleOption autoTakeoff = new ToggleOption(this, "AutoTakeoff",
            "FakeFly only: auto-jump off ground.", false);
    public final SliderOption glideDelay = new SliderOption(this, "GlideDelay",
            "FakeFly: ticks to wait after swapping the elytra in before sending the glide command (and swapping back out). Ported from lambda-client's AutoElytraSwap.glideDelay -- gives the server time to register the equip change first.", 0.0, 0.0, 20.0, 1.0);
    public final ToggleOption muteElytra = new ToggleOption(this, "MuteElytra",
            "Mutes the elytra gliding sound. Ported from lambda-client's ElytraFly.mute.", false);
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
    // tracks last known value of fakeFlyMode to detect in-flight toggles
    private boolean lastFakeFlyModeValue = false;
    // true from the moment we swap elytra in until we've either started gliding (server
    // echoed isFallFlying) or landed again -- blocks a second swap attempt mid-sequence.
    // Ported from lambda's AutoElytraSwap: swap ONCE per takeoff attempt, not repeatedly.
    private boolean cpSwapPending           = false;
    // -1 = not mid-sequence; >=0 = ticks left after the swap-in before sending the glide
    // command and swapping back out (gated by glideDelay).
    private int     cpGlideDelayTicksLeft   = -1;

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
    // Read by MixinSoundManager to cancel SoundEvents.ELYTRA_FLYING playback while
    // MuteElytra is on -- module-enabled state isn't checked there, so gate on both.
    public static volatile boolean muteElytraSound = false;

    // ── Sprint suppression while airborne ──────────────────────────────────────
    private static final String MODULE_SPRINT = "Sprint";
    private boolean sprintSaved      = false;
    private boolean savedSprintState = false;

    public ControlRocket() {
        super("ControlRocket", "Grim-compatible ControlRocket elytra flight. WASD direction with body rotation, camera free.");
    }

    /**
     * True while the module has taken off — covers BOTH real elytra gliding and ControlRocket mode
     * (where {@code isFallFlying()} is false). Used by MixinGameRenderer to suppress the first-person
     * view-bob (left/right hand sway) in every flight state, not only while gliding.
     */
    public boolean isFlying() { return flying; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        rocketCooldown = 0; elytraScreenSlot = -1; cpModeActive = false; flying = false;
        equipPending = false; equipTick = 0; pendingElytraScreenSlot = -1;
        equippedFromScreenSlot = -1; didSwapIn = false;
        pendingFire = false; invMoveBypass = false; wasGliding = false; cameraOverrideActive = false;
        muteElytraSound = muteElytra.getValue();
        lastFakeFlyModeValue = fakeFlyMode.getValue();
        cpSwapPending = false; cpGlideDelayTicksLeft = -1;

        boolean hasElytra = mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;

        if (fakeFlyMode.getValue()) {
            if (hasElytra) { flying = true; }
            else {
                int slot = findElytraScreenSlot(mc);
                if (slot == -1) { ChatHelper.sendMsg("ControlRocket", "§cNo elytra found!"); return; }
                elytraScreenSlot = slot; cpModeActive = true; flying = true;
            }
        } else {
            if (hasElytra) { flying = true; }
            else {
                int slot = findElytraScreenSlot(mc);
                if (slot == -1) { ChatHelper.sendMsg("ControlRocket", "§cNo elytra found!"); return; }
                pendingElytraScreenSlot = slot; equipPending = true; equipTick = 0;
            }
        }
    }

    @Override
    public void onDisable() {
        invMoveBypass = false;
        cameraOverrideActive = false;
        muteElytraSound = false;
        restoreSprintIfSaved(); // module turning off mid-air must not leave Sprint stuck disabled
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Restore camera if we left it at a target rotation
        if (pendingFire) {
            mc.player.setYRot(savedCameraYaw);
            mc.player.setXRot(savedCameraPitch);
        }
        pendingFire = false;

        if (cpModeActive) {
            if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA && elytraScreenSlot != -1) {
                int syncId = mc.player.inventoryMenu.containerId;
                mc.gameMode.handleContainerInput(syncId, 6, 0, ContainerInput.PICKUP, mc.player);
                mc.gameMode.handleContainerInput(syncId, elytraScreenSlot, 0, ContainerInput.PICKUP, mc.player);
                mc.gameMode.handleContainerInput(syncId, 6, 0, ContainerInput.PICKUP, mc.player);
            }
            cpModeActive = false; elytraScreenSlot = -1;
        } else if (didSwapIn && equippedFromScreenSlot != -1) {
            int syncId = mc.player.inventoryMenu.containerId;
            mc.gameMode.handleContainerInput(syncId, 6, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(syncId, equippedFromScreenSlot, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(syncId, 6, 0, ContainerInput.PICKUP, mc.player);
        }

        equippedFromScreenSlot = -1; didSwapIn = false; equipPending = false; flying = false;
        cpSwapPending = false; cpGlideDelayTicksLeft = -1;
    }

    // ── Phase 1 (Pre): set camera + body to target direction ─────────────────

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        pendingFire = false;
        if (mc.player == null || mc.level == null) return;

        muteElytraSound = muteElytra.getValue();
        updateSprintSuppression(mc);

        // Late init: onEnable fired before player was ready (e.g. module pre-enabled at world load)
        if (!flying && !equipPending && !cpModeActive) {
            lateInit(mc);
            return;
        }

        // Detect fakeFlyMode toggle while module is running — reinitialise CP state
        boolean wantCp = fakeFlyMode.getValue();
        if (wantCp != lastFakeFlyModeValue) {
            lastFakeFlyModeValue = wantCp;
            cpModeActive = false; invMoveBypass = false; rocketCooldown = 0;
            cpSwapPending = false; cpGlideDelayTicksLeft = -1;
            if (wantCp) {
                boolean hasE = mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
                if (!hasE) {
                    int s = findElytraScreenSlot(mc);
                    if (s != -1) { elytraScreenSlot = s; cpModeActive = true; flying = true; }
                }
                // hasE=true: elytra is equipped — user must manually swap elytra→inventory
                // and equip chestplate first, then CP mode will take over
            } else {
                elytraScreenSlot = -1;
                flying = mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
            }
            return;
        }

        if (equipPending) { handleEquipSequence(mc); return; }

        if (cpModeActive) {
            if (cpGlideDelayTicksLeft >= 0) {
                // Mid-sequence: elytra already swapped in, counting down to the
                // glide-trigger + swap-out (see beginChestplateSwap/finishChestplateSwap).
                if (cpGlideDelayTicksLeft == 0) {
                    finishChestplateSwap(mc);
                } else {
                    cpGlideDelayTicksLeft--;
                }
            } else if (mc.player.isFallFlying()) {
                cpSwapPending = false; // already gliding -- nothing to do until we land
            } else if (mc.player.onGround()) {
                cpSwapPending = false; // landed -- ready for a fresh swap next takeoff
                if (autoTakeoff.getValue()) mc.player.jumpFromGround();
            } else if (!cpSwapPending) {
                // Airborne, not yet gliding, haven't attempted a swap this time -- do it
                // ONCE (lambda's AutoElytraSwap pattern), not on a repeating timer. The old
                // repeating-cycle version re-clicked the container every few ticks forever,
                // which is exactly the kind of rhythmic click pattern multiplayer anti-cheat
                // flags on.
                beginChestplateSwap(mc);
                cpSwapPending = true;
            }
        } else if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            return;
        }

        if (!flying) return;
        if (!mc.player.isFallFlying() && !cpModeActive) {
            if (mc.player.onGround() && autoTakeoff.getValue()) mc.player.jumpFromGround();
            return;
        }

        // Reset cooldown when elytra gliding starts fresh so the first rocket fires immediately
        boolean nowGliding = mc.player.isFallFlying();
        if (nowGliding && !wasGliding) { rocketCooldown = 0; pendingFire = true; }
        wasGliding = nowGliding;
        
        prepDirection(mc);
    }


    // ── Sprint suppression while airborne ─────────────────────────────────────

    /**
     * Sprint causes the same kind of movement-input artifacts ControlRocket already works
     * around for the camera/hand, so it's disabled the moment ControlRocket leaves the
     * ground and restored to whatever it was the moment ControlRocket lands — on (re-enabled)
     * if it was on, left off if it was already off.
     */
    private void updateSprintSuppression(Minecraft mc) {
        boolean airborne = flying && !mc.player.onGround();
        if (airborne && !sprintSaved) {
            try {
                savedSprintState = ModuleManager.getState(MODULE_SPRINT);
                if (savedSprintState) ModuleManager.setState(MODULE_SPRINT, false);
                sprintSaved = true;
            } catch (Exception ignored) {}
        } else if (!airborne) {
            restoreSprintIfSaved();
        }
    }

    private void restoreSprintIfSaved() {
        if (!sprintSaved) return;
        try {
            if (ModuleManager.getState(MODULE_SPRINT) != savedSprintState) {
                ModuleManager.setState(MODULE_SPRINT, savedSprintState);
            }
        } catch (Exception ignored) {}
        sprintSaved = false;
    }

    // Mirrors onEnable() init logic; called when onEnable fired before player entity existed.
    private void lateInit(Minecraft mc) {
        if (mc.player == null) return;
        lastFakeFlyModeValue = fakeFlyMode.getValue();
        boolean hasE = mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
        if (fakeFlyMode.getValue()) {
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
        Minecraft mc = Minecraft.getInstance();
        if (!pendingFire || mc.player == null) return;

        pendingFire = false;  // consume before anything that could throw

        try {
            if (mc.player.onGround()) {
                rocketCooldown = 0;
            }

            if (rocketCooldown > 0) {
                rocketCooldown--;
                return;
            }

            int slot = swap.getValue() == FlySwapMode.Normal
                ? InvHelper.findInHotbar(Items.FIREWORK_ROCKET)
                : InvHelper.find(Items.FIREWORK_ROCKET);

            if (slot != -1 && mc.getConnection() != null) {
                InvHelper.swapToSlot(slot, getSwapType());
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                InvHelper.swapBack();

                rocketCooldown = mc.player.onGround() ? 0 : conserveDelay.getValue().intValue();
            }
        } finally {
            // Always restore camera, even if rocket failed / no fireworks
            mc.player.setYRot(savedCameraYaw);
            mc.player.setXRot(savedCameraPitch);
            // yRotO/xRotO are set to targetYaw/targetPitch by the entity tick that ran
            // between Pre and Post. Reset them to the natural values so getPitch(tickDelta)
            // interpolates between identical values → first-person arms don't fling.
            mc.player.yRotO = savedCameraYaw;
            mc.player.xRotO = savedCameraPitch;
            // Release the mixin override — camera is back to natural rotation.
            cameraOverrideActive = false;
        }
    }

    // ── Direction setup ───────────────────────────────────────────────────────

    private void prepDirection(Minecraft mc) {
        savedCameraYaw   = mc.player.getYRot();
        savedCameraPitch = mc.player.getXRot();

        // Third-person-front (F5 x2, "mirrored") camera renders from IN FRONT of the
        // player looking back at them -- vanilla's own Camera.setPosition does this by
        // rendering at (entity.yRot + 180, -entity.xRot) whenever CameraType.isMirrored()
        // (verified via javap on Camera.class, minecraft-merged-1c9175fa40-26.1.2.jar:
        // the isMirrored() branch calls setRotation(yRot+180, -xRot)). WASD direction here
        // was built straight off player.getYRot(), which is only equal to what the player
        // actually SEES in first-person/third-back; in mirrored view it's 180° off from the
        // real camera facing, so W walked the player AWAY from what they were looking at
        // (user report, 2026-07-19). Only the movement-basis yaw needs the correction --
        // savedCameraYaw/Pitch (restored in Post, read by MixinEntity) stay the real raw
        // player rotation, since vanilla's own mirrored-camera math already derives the
        // correct render angle from that raw value on its own.
        float moveYaw = savedCameraYaw;
        if (mc.options.getCameraType().isMirrored()) moveYaw += 180f;

        double yawRad = Math.toRadians(moveYaw);
        double sinYaw = Math.sin(yawRad), cosYaw = Math.cos(yawRad);

        double dx = 0, dz = 0;
        if (mc.options.keyUp.isDown())    { dx -= sinYaw; dz += cosYaw; }
        if (mc.options.keyDown.isDown())  { dx += sinYaw; dz -= cosYaw; }
        if (mc.options.keyLeft.isDown())  { dx += cosYaw; dz += sinYaw; }
        if (mc.options.keyRight.isDown()) { dx -= cosYaw; dz -= sinYaw; }

        double  len   = Math.sqrt(dx * dx + dz * dz);
        boolean hasH  = len > 0.01;
        boolean space = mc.options.keyJump.isDown();
        boolean shift = mc.options.keyShift.isDown();

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
                double vy = mc.player.getDeltaMovement().y;
                targetPitch = (float) Math.max(-20.0, Math.min(20.0, vy * 50.0 - 7.0));
            }
        } else if (space) {
            targetYaw = savedCameraYaw; targetPitch = -90f;
        } else if (shift) {
            targetYaw = savedCameraYaw; targetPitch = 90f;
        } else if (tryHold.getValue() == TryHoldMode.On && mc.player.getDeltaMovement().y < -0.1 && rocketCooldown <= 0) {
            targetYaw = savedCameraYaw; targetPitch = -20f;
        } else {
            return;
        }

        // Rotate player to targetYaw so sendMovementPackets() (called during the
        // player tick between Pre and Post) sends a move packet with targetYaw.
        // The server updates its player rotation → when the rocket fires in Post,
        // the server uses targetYaw for the boost direction.
        mc.player.setYRot(targetYaw);
        mc.player.setXRot(targetPitch);

        // Body faces the flight direction visually. NOT restored in Post so the
        // body keeps facing targetYaw until Minecraft's interpolation brings it
        // back toward camera yaw — matching ControlRocket's body-turn behaviour.
        mc.player.setYBodyRot(targetYaw);

        // Signal MixinEntity to intercept getPitch/getYaw for this player until Post
        // restores the camera. This ensures the first-person arm renderer always sees
        // the real camera rotation even while the entity's rotation is set to targetPitch/Yaw.
        cameraOverrideActive = true;
        pendingFire = true;
    }

    // ── FakeFly mode ─────────────────────────────────────────────────────────

    /**
     * Swap chestplate → elytra (single atomic 3-click, same as swapOut just run on the
     * same slot index) and start the glideDelay countdown. Called ONCE per takeoff
     * attempt -- see cpSwapPending in onTickPre.
     */
    private void beginChestplateSwap(Minecraft mc) {
        if (mc.screen != null || elytraScreenSlot == -1) return;

        if (mc.player.inventoryMenu.getSlot(elytraScreenSlot).getItem().getItem() != Items.ELYTRA) {
            int newSlot = findElytraScreenSlot(mc);
            if (newSlot == -1) {
                ChatHelper.sendMsg("ControlRocket", "§cElytra lost!");
                cpModeActive = false; flying = false; return;
            }
            elytraScreenSlot = newSlot;
        }

        swapChestWithSlot(mc, elytraScreenSlot);
        cpGlideDelayTicksLeft = glideDelay.getValue().intValue();
    }

    /**
     * Send the glide command, then swap elytra → chestplate (the same 3-click routine on
     * the same slot index toggles it back, since the chestplate ended up in
     * elytraScreenSlot after beginChestplateSwap). Called once, after glideDelay ticks.
     */
    private void finishChestplateSwap(Minecraft mc) {
        cpGlideDelayTicksLeft = -1;
        if (mc.getConnection() != null) {
            mc.getConnection().send(
                new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
        swapChestWithSlot(mc, elytraScreenSlot);
    }

    /** Atomic 3-click toggle between the chest slot (6) and {@code otherSlot}. */
    private void swapChestWithSlot(Minecraft mc, int otherSlot) {
        int syncId = mc.player.inventoryMenu.containerId;
        invMoveBypass = true;
        try {
            mc.gameMode.handleContainerInput(syncId, otherSlot, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(syncId, 6, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(syncId, otherSlot, 0, ContainerInput.PICKUP, mc.player);
        } finally {
            invMoveBypass = false;
        }
    }

    // ── Normal-mode equip sequence ─────────────────────────────────────────────

    private void handleEquipSequence(Minecraft mc) {
        if (mc.screen != null) return;
        equipTick++;
        if (equipTick == 1) {
            int syncId = mc.player.inventoryMenu.containerId;
            mc.gameMode.handleContainerInput(syncId, pendingElytraScreenSlot, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(syncId, 6, 0, ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(syncId, pendingElytraScreenSlot, 0, ContainerInput.PICKUP, mc.player);
            return;
        }
        equipPending = false;
        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            ChatHelper.sendMsg("ControlRocket", "§cFailed to equip elytra."); return;
        }
        equippedFromScreenSlot  = pendingElytraScreenSlot;
        didSwapIn               = true;
        pendingElytraScreenSlot = -1;
        flying                  = true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int findElytraScreenSlot(Minecraft mc) {
        for (int i = 9; i <= 44; i++) {
            if (mc.player.inventoryMenu.getSlot(i).getItem().getItem() == Items.ELYTRA) return i;
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
