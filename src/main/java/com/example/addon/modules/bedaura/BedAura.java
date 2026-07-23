package com.example.addon.modules.bedaura;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.event.EventInteract;
import dev.boze.api.event.EventRotate;
import dev.boze.api.event.EventTick;
import dev.boze.api.utility.interaction.Interaction;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.PageOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class BedAura extends AddonModule {
    public static final BedAura INSTANCE = new BedAura();

    public enum TargetMode { Closest, Furthest, Health }

    public final ToggleOption airPlace = new ToggleOption(this, "AirPlace",
        "Allow placing the bed against air (no adjacent solid block needed).", false);

    // Off by default: a candidate overlapping the target's own hitbox can fail to actually
    // place (PlaceHelper.place() has a stricter internal entity-overlap guard than manual
    // placement against some targets). Kept as a toggle since behavior can vary by server.
    public final ToggleOption placeOnFeet = new ToggleOption(this, "PlaceOnFeet",
        "Allow placement candidates that overlap the target's own hitbox. Off by default -- "
        + "such placements reliably fail to actually place a real block (see class notes).", false);

    // Mirrors Mint's own StrictDirection toggle, passed into PlaceHelper.cast(): when true,
    // cast() requires the player's real aim to geometrically line up with the clicked face.
    public final ToggleOption strictDirection = new ToggleOption(this, "StrictDirection",
        "Require PlaceHelper.cast()'s strict direction check to pass (real aim must line "
        + "up with the clicked face).", false);


    public final ModeOption<TargetMode> target = new ModeOption<>(this, "Target",
        "How to rank players when picking which ones to consider (see MaxTargets).", TargetMode.Closest);

    public final ToggleOption ignoreFriends = new ToggleOption(this, "IgnoreFriends",
        "Never target players on your Boze friends list.", true);

    // Mirrors Mint's own targetRange/maxTargets/updateTargets + multi-target findPlace: keeps
    // a whole RANKED LIST of up to MaxTargets targets (within TargetRange, separate from and
    // usually wider than Range/WallsRange), and findPlace scores every (candidate, target)
    // pair across the whole list -- not "best candidate for one pre-picked target".
    public final SliderOption targetRange = new SliderOption(this, "TargetRange",
        "Range within which to consider targets at all (separate from Range/WallsRange, which "
        + "gate whether a specific PLACEMENT is reachable).", 8.0, 1.0, 16.0, 0.5);

    public final SliderOption maxTargets = new SliderOption(this, "MaxTargets",
        "Max number of ranked targets to search placements for at once.", 3.0, 1.0, 8.0, 1.0);

    // Search CUBE radius around a target's block position (separate from exReach). Mint
    // exposes this as a slider (1.0-8.0, default 4.0); hardcoded here at its default value.
    private static final double BED_RANGE = 4.0;

    public final ModeOption<InteractionMode> placeMode = new ModeOption<>(this, "PlaceMode",
        "Anti-cheat handler used for place/detonate rotations.", InteractionMode.NCP);

    public final ModeOption<SwapType> swapMode = new ModeOption<>(this, "Switch",
        "How to swap the bed into hand before placing.", SwapType.Normal);

    public final ToggleOption rotate = new ToggleOption(this, "Rotate",
        "Rotate toward the placement/detonation point before acting.", true);

    public final ToggleOption pauseOnEat = new ToggleOption(this, "PauseOnEat",
        "Don't place/detonate while eating.", false);

    // AutoMine's InstantMine mode re-breaks whatever sits at its target position immediately,
    // destroying a freshly-placed bed before it can detonate. AutoMineHelper.setCanBreak is
    // ADDITIVE ONLY (can grant extra breakable blocks, never blacklist one) so it can't stop
    // this. Instead: switch AutoMine's own "Instant" mode option to Strict while a bed is
    // down, restore the original value right after detonating (see findAutoMineModeOption).
    public final ToggleOption suppressAutoMine = new ToggleOption(this, "SuppressAutoMine",
        "Switch AutoMine's Mode to Strict while a bed is placed (restoring the original mode "
        + "after detonating), so InstantMine can't destroy it before detonation.", true);

    public final SliderOption delay = new SliderOption(this, "Delay",
        "Milliseconds between placing the bed and right-clicking it, and between cycles.",
        150.0, 0.0, 500.0, 5.0);

    public final SliderOption range = new SliderOption(this, "Range",
        "Reach (blocks) for both placing the bed and right-clicking it.", 4.0, 0.0, 6.0, 0.1);

    // Separate from range: PlaceHelper.cast()'s wallsRange param is its own independent
    // reach for hitting a face through/around geometry the direct-sight range check misses.
    public final SliderOption wallsRange = new SliderOption(this, "WallsRange",
        "Reach (blocks) PlaceHelper.cast() uses for its walls/through-geometry check, independent of Range.", 4.0, 0.0, 6.0, 0.1);

    // 0-20 = actual HP the hit costs post-armor, matching a player's max health -- NOT the
    // raw pre-mitigation blast value (which can read up to ~71 point-blank). DamageUtils.
    // estimateHpLoss applies armor mitigation so these compare against real HP loss.
    public final SliderOption minDamage = new SliderOption(this, "MinDamage",
        "Minimum estimated HP damage (post-armor) required to trigger a placement.", 6.0, 0.0, 20.0, 0.5);

    public final SliderOption maxDamage = new SliderOption(this, "MaxDamage",
        "Maximum estimated HP damage (post-armor) to YOURSELF -- placements that would cost more HP than this are skipped.", 6.0, 0.0, 20.0, 0.5);

    public final SliderOption predict = new SliderOption(this, "Predict",
        "Ticks to extrapolate the target's position ahead using its current velocity.",
        2.0, 0.0, 10.0, 1.0);

    public final PageOption autoCraft = new PageOption(this, "AutoCraft",
        "One-shot: craft beds from wool + planks in your inventory.");
    // PageOption itself has no bind concept (verified: PageOption/ParentOption source
    // has no bind-related fields or methods at all) -- AddonModule.getBindOption()
    // only exists for the MODULE's own single toggle bind. A dedicated BindOption,
    // parented to autoCraft for UI grouping, is the real mechanism.
    public final dev.boze.api.option.BindOption autoCraftBind = new dev.boze.api.option.BindOption(
        this, "AutoCraftBind", "Keybind to trigger AutoCraft.", -1, false, autoCraft);
    public final ToggleOption autoPlace = new ToggleOption(this, "AutoPlace",
        "Place and open a crafting table automatically if you have zero beds.", true, autoCraft);
    public final ToggleOption autoClose = new ToggleOption(this, "AutoClose",
        "Close the crafting table GUI automatically once crafting is done.", true, autoCraft);

    public final PageOption render = new PageOption(this, "Render",
        "Preview box for the bed placement spot.");
    public final ToggleOption interpolate = new ToggleOption(this, "Interpolate",
        "Linear grow-in animation for the preview box.", true, render);

    public BedAura() {
        super("BedAura", "Automated bed-clutch PvP: targets, predicts, places, and detonates beds in the nether/end.");
    }

    @Override
    public void onEnable() {
        autoMineSuppressed = false;
        suppressAutoMineBroken = false;
        lastLoggedNoBed = false;
        lastLoggedNoBedSlot = false;
        clearLock();
        // Suppress for the module's whole enabled lifetime, not per bed place/detonate cycle --
        // doing it per-cycle flipped AutoMine's Instant option Strict<->saved every single
        // clutch (visible thrashing during continuous bed-clutching against a target).
        suppressAutoMineIfNeeded();
    }

    @Override
    public void onDisable() {
        restoreAutoMineIfNeeded();
    }

    // Decide (search/lock-check) and act (place/detonate) run from the SAME onInteract
    // cadence below, matching Mint exactly -- splitting them across two different event
    // cadences let the lock get re-evaluated/overwritten before ever being acted on once.

    private net.minecraft.core.BlockPos lockFoot = null;
    private Direction lockDir = null;
    private Player lockTarget = null;
    private float lockEstimatedDamage = 0f;
    private long lastUpgradeCheckMs = 0;
    private long lastReacquireSearchMs = 0;
    // A lock acquired under bad conditions (mid-craft, not yet in position) otherwise sticks
    // forever, since lockValid only checks whether it's still valid, never whether something
    // better now exists. Periodically (throttled, not every tick) compare against a fresh
    // search and switch if it's a real improvement, only while nothing's been placed yet.
    private static final long UPGRADE_CHECK_INTERVAL_MS = 500;
    private static final float UPGRADE_MIN_GAIN = 2.0f;
    // The full O(r^3) search is expensive (AirPlace especially, since it skips the cheap
    // floor-check that would otherwise early-reject most candidates before the costly
    // per-candidate occlusion sampling). lockValid() can flicker invalid every tick while
    // moving (eye-position-based reach checks shift continuously), which without this
    // throttle reran the full search completely unbounded -- the actual cause of "AirPlace +
    // moving tanks fps", not the upgrade-check interval above.
    private static final long REACQUIRE_SEARCH_MIN_INTERVAL_MS = 300;


    private void clearLock() {
        lockFoot = null;
        lockDir = null;
        lockTarget = null;
        lockEstimatedDamage = 0f;
    }

    /**
     * Mirrors 0tterware/Boze-Mint-Addon's own lockValid() exactly: only checks whether the
     * CURRENTLY locked (foot,dir) still reaches/damages/is-safe -- deliberately does NOT
     * check live block state (WorldHelper.isReplaceable etc), same as Mint. If the locked
     * block itself became unplaceable (fire spread onto it, say), the actual place attempt
     * simply fails that cycle and retries the SAME spot next cycle rather than jumping
     * elsewhere -- self-heals once the terrain clears instead of chasing a moving target.
     */
    private boolean lockValid(Minecraft mc) {
        if (lockFoot == null || lockDir == null || lockTarget == null) return false;
        if (!lockTarget.isAlive() || lockTarget.getHealth() <= 0) return false;
        if (ignoreFriends.getValue() && dev.boze.api.client.FriendManager.isFriend(lockTarget.getName().getString())) return false;

        // The TARGET itself must stay within TargetRange (its own, usually-wider radius),
        // independent of whether the locked BLOCK position is still within Range/WallsRange.
        if (lockTarget.distanceToSqr(mc.player) > (targetRange.getValue() + 1.0) * (targetRange.getValue() + 1.0)) return false;

        double exReach = Math.max(range.getValue(), wallsRange.getValue());
        net.minecraft.core.BlockPos head = lockFoot.relative(lockDir);
        boolean reach = withinReach(mc, lockFoot, exReach) || withinReach(mc, head, exReach);
        if (!reach) return false;

        Vec3 footCenter = lockFoot.getCenter(), headCenter = head.getCenter();
        float dFoot = DamageUtils.estimateHpLoss(footCenter, lockTarget, predict.getValue());
        float dHead = DamageUtils.estimateHpLoss(headCenter, lockTarget, predict.getValue());
        boolean useHead = dHead >= dFoot;
        Vec3 detCenter = useHead ? headCenter : footCenter;
        float dmg = Math.max(dFoot, dHead);
        if (dmg < minDamage.getValue()) return false;

        float selfDmg = DamageUtils.estimateHpLoss(detCenter, mc.player, predict.getValue());
        if (selfDmg > maxDamage.getValue()) return false;
        lockEstimatedDamage = dmg;
        return true;
    }

    /**
     * Mirrors Mint's own withinReach: measures from the player's EYE position, not raw
     * entity position -- eye height matters for diagonal/vertically-offset arrangements.
     * Adds Mint's +0.5 reach fudge and its InteractionMode.Grim bypass (Grim's own reach
     * validation works differently server-side, so client-side doesn't second-guess it).
     */
    private boolean withinReach(Minecraft mc, net.minecraft.core.BlockPos pos, double reach) {
        if (placeMode.getValue() == InteractionMode.Grim) return true;
        return pos.getCenter().distanceTo(mc.player.getEyePosition()) <= reach + 0.5;
    }

    // Ranked list of up to MaxTargets players, refreshed once per cycle by updateTargets().
    // findBestPlacementJoint searches placements across this whole list, not one pre-picked target.
    private final java.util.List<Player> targets = new java.util.ArrayList<>();

    /**
     * Refills {@link #targets} with up to {@link #maxTargets} players within {@link #targetRange}
     * (separate from, and usually much wider than, Range/WallsRange), ranked by {@link #target}'s
     * mode. Iterates entitiesForRendering() rather than ClientLevel.players() -- a client-side
     * test dummy added via addEntity() never appears in players(), only entitiesForRendering().
     */
    private void updateTargets(Minecraft mc) {
        targets.clear();
        Vec3 eye = mc.player.getEyePosition();

        // Literal port of Mint's own updateTargets: iterates entitiesForRendering() (not a
        // bounding-box query), and the distance gate is an OR of two different measures
        // (block-center-to-eye, and raw entity.distanceTo(player)) -- a target only gets
        // dropped if BOTH read beyond TargetRange, not just one. getEntities()+AABB.inflate()
        // was an equivalent-ish approximation, not a literal match.
        java.util.List<Player> found = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player p)) continue;
            if (p == mc.player) continue;
            if (ignoreFriends.getValue() && dev.boze.api.client.FriendManager.isFriend(p.getName().getString())) continue;
            if (!p.isAlive() || p.getHealth() <= 0) continue;
            if (p.blockPosition().getCenter().distanceTo(eye) > targetRange.getValue()
                    && p.distanceTo(mc.player) > targetRange.getValue()) continue;
            found.add(p);
        }

        java.util.Comparator<Player> comparator = switch (target.getValue()) {
            case Furthest -> java.util.Comparator.comparingDouble((Player p) -> p.distanceTo(mc.player)).reversed();
            case Health -> java.util.Comparator.comparingDouble(Player::getHealth);
            case Closest -> java.util.Comparator.comparingDouble(p -> p.distanceTo(mc.player));
        };
        found.sort(comparator);

        int max = maxTargets.getValue().intValue();
        for (Player p : found) {
            if (targets.size() >= max) break;
            targets.add(p);
        }
    }

    public record PlacementCandidate(net.minecraft.core.BlockPos pos, Direction dir, float estimatedDamage) {}

    /** Reads real post-placement block state to find {@code foot}'s other bed half (ground truth, not a guess about which direction the server picked). Null if no bed neighbor found. */
    private static net.minecraft.core.BlockPos findOtherBedHalf(Minecraft mc, net.minecraft.core.BlockPos foot) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            net.minecraft.core.BlockPos neighbour = foot.relative(d);
            if (mc.level.getBlockState(neighbour).getBlock() instanceof net.minecraft.world.level.block.BedBlock) return neighbour;
        }
        return null;
    }

    public record JointPlacement(net.minecraft.core.BlockPos foot, Direction dir, Player target, float estimatedDamage) {}

    // 2026-07-19 fix ("AirPlace + FakePlayer trên không gây khựng màn hình"): with AirPlace on,
    // the cheap floor-below pre-reject (see the `!airPlace.getValue() && isReplaceable(below)`
    // line below) is deliberately skipped for every one of the up to targets.size() * (2r+1)^3
    // candidates -- there's no cheap early-out left for a floating/open-air search, so the full
    // sweep runs at genuinely higher cost every time it fires. The existing
    // REACQUIRE_SEARCH_MIN_INTERVAL_MS throttle only bounds how OFTEN a full sweep runs, not
    // how long any single sweep takes -- so with AirPlace on on a target floating in open air,
    // every ~300ms still paid the FULL O(r^3) cost synchronously in one frame, showing up as a
    // periodic hitch (visible in this addon's own animated nether dust). Fix: turn the sweep
    // into a resumable step function that only evaluates SEARCH_BUDGET_PER_CALL candidates per
    // call, spreading the same total work across several onInteract calls instead of one frame.
    private static final int SEARCH_BUDGET_PER_CALL = 200;
    private java.util.List<Player> searchTargetsSnapshot = null;
    private int searchFlatIndex = 0;
    private JointPlacement searchBest = null;
    private double searchBestScore = -1.0;

    /** True while a chunked sweep is mid-flight (started, not yet finished evaluating every candidate). */
    private boolean isSearching() {
        return searchTargetsSnapshot != null;
    }

    /**
     * Resumable replacement for the old single-call searchPlacementJoint: evaluates at most
     * {@link #SEARCH_BUDGET_PER_CALL} candidates per invocation and returns null while more
     * remain (check {@link #isSearching()} to tell "still working" apart from "finished, found
     * nothing"). {@link #targets} is snapshotted at the start of a sweep -- it's rebuilt fresh
     * every onInteract cycle by updateTargets(), so a live reference would shift size/contents
     * out from under a mid-sweep flat index.
     * <p>
     * Same search space and scoring as before (every (candidate, target) pair, keeping
     * whichever single combination scores best overall -- mirrors Mint's findPlace; for every
     * (foot, dir) pair, scores by max(dFoot, dHead) so the head can be forced into whichever
     * cell deals more damage).
     */
    private JointPlacement stepSearch(Minecraft mc) {
        int r = (int) Math.ceil(BED_RANGE);
        int side = 2 * r + 1;
        int cubeSize = side * side * side;

        if (searchTargetsSnapshot == null) {
            searchTargetsSnapshot = new java.util.ArrayList<>(targets);
            searchFlatIndex = 0;
            searchBest = null;
            searchBestScore = -1.0;
            if (searchTargetsSnapshot.isEmpty()) {
                searchTargetsSnapshot = null;
                return null;
            }
        }

        double minD = minDamage.getValue(), maxD = maxDamage.getValue();
        double exReach = Math.max(range.getValue(), wallsRange.getValue());
        // Restrict the head direction to the player's current facing when Rotate is off --
        // otherwise a placement could require a rotation that never happens.
        Direction only = rotate.getValue() ? null : Direction.fromYRot(mc.player.getYRot());
        int total = searchTargetsSnapshot.size() * cubeSize;

        int evaluated = 0;
        while (searchFlatIndex < total && evaluated < SEARCH_BUDGET_PER_CALL) {
            int i = searchFlatIndex++;
            evaluated++;

            int targetIdx = i / cubeSize;
            int local = i % cubeSize;
            int dx = local / (side * side) - r;
            int rem1 = local % (side * side);
            int dz = rem1 / side - r;
            int dy = rem1 % side - r;

            Player targetPlayer = searchTargetsSnapshot.get(targetIdx);
            // Raw current blockPosition(), never predicted -- predict only feeds the damage
            // estimate below, moving the search cube itself would drag it off a moving target.
            net.minecraft.core.BlockPos center = targetPlayer.blockPosition();
            net.minecraft.core.BlockPos foot = center.offset(dx, dy, dz);

            // cast() can reach as far as wallsRange even when it exceeds range, so gating
            // candidate selection on range alone pre-rejects reachable spots.
            if (!withinReach(mc, foot, exReach)) continue;
            if (!dev.boze.api.utility.WorldHelper.isInWorldBounds(foot) || !dev.boze.api.utility.WorldHelper.isRegionLoaded(foot)) continue;
            // WorldHelper.isReplaceable (Boze wrapper), not vanilla canBeReplaced() --
            // the two aren't guaranteed equivalent (closed-source internal impl).
            if (!dev.boze.api.utility.WorldHelper.isReplaceable(foot)) continue;
            if (!dev.boze.api.utility.WorldHelper.canPlaceAt(foot)) continue;
            // isReplaceable only asks "can this block be overwritten" -- says nothing
            // about whether a BED specifically is legal to place here.
            if (!dev.boze.api.utility.WorldHelper.isValidPlacement(foot, net.minecraft.world.level.block.Blocks.WHITE_BED)) continue;
            if (!PlaceHelper.isEmpty(foot)) continue;
            // A candidate at mc.player's own position can't be clicked -- own body
            // occupies the space. isEmpty(foot) above already excludes entity overlap
            // in general; this specifically covers the player (intoTarget only affects HEAD).
            if (new net.minecraft.world.phys.AABB(foot).intersects(mc.player.getBoundingBox())) continue;
            // Grounded and floating candidates compete on score alone, no bias either way.
            if (!airPlace.getValue() && dev.boze.api.utility.WorldHelper.isReplaceable(foot.below())) continue;

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (only != null && dir != only) continue;
                net.minecraft.core.BlockPos head = foot.relative(dir);
                // Mint's headPlaceable: only isReplaceable(head) is mandatory; when
                // placeOnFeet is on, no isValidPlacement/isEmpty/entity check at all --
                // needed so the head can land inside a target's own hitbox for max damage.
                if (!dev.boze.api.utility.WorldHelper.isReplaceable(head)) continue;
                if (new net.minecraft.world.phys.AABB(head).intersects(mc.player.getBoundingBox())) continue;
                if (!placeOnFeet.getValue()) {
                    if (!PlaceHelper.isEmpty(head)) continue;
                    if (new net.minecraft.world.phys.AABB(head).intersects(targetPlayer.getBoundingBox())) continue;
                }

                Vec3 footCenter = foot.getCenter();
                Vec3 headCenter = head.getCenter();
                float dFootTarget = DamageUtils.estimateHpLoss(footCenter, targetPlayer, predict.getValue());
                float dHeadTarget = DamageUtils.estimateHpLoss(headCenter, targetPlayer, predict.getValue());
                boolean useHead = dHeadTarget >= dFootTarget;
                Vec3 detonateCenter = useHead ? headCenter : footCenter;
                float dmg = Math.max(dFootTarget, dHeadTarget);
                if (dmg < minD) continue;

                float selfDmg = DamageUtils.estimateHpLoss(detonateCenter, mc.player, predict.getValue());
                if (selfDmg > maxD) continue;

                // Small tie-break toward the head landing closer to the target --
                // doesn't override a genuinely better-damage combination.
                double score = dmg;
                if (headCenter.distanceTo(targetPlayer.position()) < footCenter.distanceTo(targetPlayer.position())) score += 0.01;

                if (searchBest == null || score > searchBestScore) {
                    searchBest = new JointPlacement(foot, dir, targetPlayer, dmg);
                    searchBestScore = score;
                }
            }
        }

        if (searchFlatIndex < total) return null; // more candidates left -- caller checks isSearching()

        JointPlacement result = searchBest;
        searchTargetsSnapshot = null;
        searchBest = null;
        searchBestScore = -1.0;
        return result;
    }


    /** The currently-selected best placement spot, if any -- read by the render handler. */
    PlacementCandidate currentPlacement = null;

    private boolean lastLoggedNoBed = false;
    // Edge-triggered: Normal/Silent swap needs the bed in the HOTBAR specifically
    // (Inventory.setSelectedSlot only accepts a hotbar index) -- if it's only in main
    // inventory, this fails every single cycle since the bed never moves on its own.
    // Log once, not every retry (use Alt swap mode instead if the bed isn't in the hotbar).
    private boolean lastLoggedNoBedSlot = false;

    private long lastActionMs = 0;
    private boolean bedPlacedThisCycle = false;
    private net.minecraft.core.BlockPos placedBedPos = null;
    // The bed's OTHER half, found by reading real post-placement block state (ground truth,
    // not a guess about which direction the server picked) -- lets detonate() click whichever
    // half is physically closer to the target, since head-direction is server-determined.
    private net.minecraft.core.BlockPos placedBedHeadPos = null;
    private net.minecraft.core.BlockPos lastPlacedAnchorPos = null;

    // World point AutoCraft's table-open faces (bed place/detonate use onInteract's atomic
    // Interaction rotation instead; a crafting table has no FACING blockstate to race on).
    private Vec3 rotateTarget = null;

    @EventHandler
    private void onRotate(EventRotate event) {
        if (!rotate.getValue()) return;
        if (!event.isFree()) return; // never fight a rotation another feature already owns
        if (rotateTarget != null) event.rotate(rotateTarget);
    }

    /**
     * dev.boze.api.event.EventInteract fires "when the client is fetching interactions".
     * Mint's entire place/detonate cycle -- decision (target search, lock validity, findPlace)
     * AND action (place/detonate) -- runs from this one handler, so it can never decide
     * faster than it can act. Matched here: no separate tick handler driving the search.
     */
    @EventHandler
    private void onInteract(EventInteract event) {
        if (event.getMode() != placeMode.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        try {
            runBedCycle(event, mc);
        } catch (Exception e) {
            // Surface exceptions instead of letting the cycle die silently mid-pipeline.
            dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "§conInteract exception: " + e);
        }
    }

    private void runBedCycle(EventInteract event, Minecraft mc) {
        ResourceKey<Level> dim = mc.player.level().dimension();
        if (dim != Level.NETHER && dim != Level.END) { currentPlacement = null; return; }

        if (pauseOnEat.getValue() && mc.player.isUsingItem()
                && mc.player.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD)) {
            return;
        }

        long now = System.currentTimeMillis();
        if ((now - lastActionMs) < delay.getValue()) return;

        if (bedPlacedThisCycle) {
            submitDetonate(event, mc);
            return;
        }

        // Bail before searching/rendering at all when there's no bed to place.
        boolean hasBed = InvHelper.find(stack -> !stack.isEmpty()
            && stack.getItem() instanceof net.minecraft.world.item.BedItem) != -1;
        if (!hasBed) {
            if (!lastLoggedNoBed) {
                dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "no bed in inventory");
                lastLoggedNoBed = true;
            }
            currentPlacement = null;
            rotateTarget = null;
            return;
        }
        lastLoggedNoBed = false;

        updateTargets(mc);
        if (targets.isEmpty()) { currentPlacement = null; rotateTarget = null; clearLock(); return; }

        // Sticky lock: keeps the same (foot,dir) as long as lockValid() still holds, only
        // re-searching when it truly breaks -- mirrors Mint's own lockFoot/lockDir/lockValid()/
        // acquireLock(). Additionally (throttled upgrade check, not pure Mint behavior): while
        // still just searching (nothing placed yet), periodically compare against a fresh
        // search and switch if it's a real improvement -- otherwise a lock acquired under bad
        // conditions (mid-craft, not yet in position) sticks forever even once things improve.
        boolean valid = lockValid(mc);
        boolean dueForUpgradeCheck = valid && !bedPlacedThisCycle
            && (now - lastUpgradeCheckMs) >= UPGRADE_CHECK_INTERVAL_MS;

        if (!valid) {
            // Don't START a new sweep more than once per throttle window -- lockValid can
            // flicker invalid every tick while moving (see field doc). Once a sweep IS running
            // (isSearching()), keep stepping it every call regardless of the throttle: each
            // step is budget-capped/cheap now (see stepSearch's doc), so there's no cost
            // reason to wait, and waiting would just make reacquisition slower.
            if (!isSearching() && (now - lastReacquireSearchMs) < REACQUIRE_SEARCH_MIN_INTERVAL_MS) return;
            JointPlacement jointPlacement = stepSearch(mc);
            if (isSearching()) return; // more candidates left -- finish next call(s)
            lastReacquireSearchMs = now;
            if (jointPlacement == null) { currentPlacement = null; rotateTarget = null; clearLock(); return; }
            lockFoot = jointPlacement.foot();
            lockDir = jointPlacement.dir();
            lockTarget = jointPlacement.target();
            lockEstimatedDamage = jointPlacement.estimatedDamage();
            currentPlacement = new PlacementCandidate(lockFoot, lockDir, jointPlacement.estimatedDamage());
        } else {
            if (dueForUpgradeCheck) {
                JointPlacement better = stepSearch(mc);
                if (!isSearching()) {
                    // Only reset the timer once the sweep actually finishes -- while chunking,
                    // dueForUpgradeCheck stays true so the next call(s) keep stepping the SAME
                    // sweep instead of starting a fresh one.
                    lastUpgradeCheckMs = now;
                    if (better != null && better.estimatedDamage() > lockEstimatedDamage + UPGRADE_MIN_GAIN) {
                        lockFoot = better.foot();
                        lockDir = better.dir();
                        lockTarget = better.target();
                        lockEstimatedDamage = better.estimatedDamage();
                    }
                }
            }
            currentPlacement = new PlacementCandidate(lockFoot, lockDir, lockEstimatedDamage);
        }

        submitPlaceBed(event, mc, lockFoot, lockDir);
    }

    private void submitPlaceBed(EventInteract event, Minecraft mc, net.minecraft.core.BlockPos pos, Direction dir) {
        BlockHitResult hit = computeBedHit(mc, pos);
        // clearLock() on a failed hit (mirrors Mint's placeLock) -- lockValid() never rechecks
        // floor/block state, so without this a placement that keeps failing (e.g. AirPlace
        // toggled off after this spot was locked in while floating) would retry the same
        // dead BlockPos forever instead of ever re-searching.
        if (hit == null) { clearLock(); return; }
        float yaw = dir.toYRot();
        float pitch = dev.boze.api.utility.MathHelper.calculateRotation(mc.player.getEyePosition(), hit.getLocation())[1];
        Runnable action = () -> {
            if (executePlaceBed(mc, pos, hit)) {
                bedPlacedThisCycle = true;
                placedBedPos = lastPlacedAnchorPos;
                placedBedHeadPos = findOtherBedHalf(mc, lastPlacedAnchorPos);
                lastActionMs = System.currentTimeMillis();
                suppressAutoMineIfNeeded();
            }
        };
        submitRotated(event, action, yaw, pitch);
    }

    private void submitDetonate(EventInteract event, Minecraft mc) {
        if (placedBedPos == null) return;
        net.minecraft.core.BlockPos target = placedBedPos;
        if (placedBedHeadPos != null) {
            // Use lockTarget (who this bed was scored against), not a fresh re-pick.
            Player detTarget = lockTarget;
            if (detTarget != null && detTarget.distanceToSqr(placedBedHeadPos.getCenter()) < detTarget.distanceToSqr(placedBedPos.getCenter())) {
                target = placedBedHeadPos;
            }
        }
        net.minecraft.core.BlockPos finalTarget = target;
        BlockHitResult hit = new BlockHitResult(target.getCenter(), Direction.UP, target, false);
        float[] rot = dev.boze.api.utility.MathHelper.calculateRotation(mc.player.getEyePosition(), hit.getLocation());
        Runnable action = () -> {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            bedPlacedThisCycle = false;
            placedBedPos = null;
            placedBedHeadPos = null;
            currentPlacement = null;
            lastActionMs = System.currentTimeMillis();
        };
        submitRotated(event, action, rot[0], rot[1]);
    }

    // AutoMine mode suppression for BedAura's whole enabled lifetime: switch AutoMine's own
    // "Instant" mode option to Strict on enable, so it isn't in the specific mode that
    // instant-breaks a freshly-placed bed before it can detonate; restore the saved original
    // mode on disable. NOT scoped per place/detonate cycle -- that flipped the option on
    // every single clutch during continuous bed-fighting (visible thrashing).
    private static final String MODULE_AUTOMINE = "AutoMine";
    private static final String TARGET_MODE_NAME = "Strict";
    private boolean autoMineSuppressed = false;
    private boolean suppressAutoMineBroken = false;
    private boolean autoMineActuallyForced = false; // true only if we called LiveModeCache.suppressAutoMine()

    // ModeOption.setValueByName silently no-ops on an unknown name (no exception, no return
    // value), so the actual switch is verified below rather than assumed from "no exception".
    // getClientModule(name) is the field-verified lookup for a built-in Boze client module
    // (same pattern as PathFinder.java's ElytraFly lookup).
    private dev.boze.api.client.module.BaseModule findAutoMineModule() {
        return ModuleManager.getClientModule(MODULE_AUTOMINE);
    }

    // The relevant option is literally named "Instant" (under AutoMine's ReMine section) --
    // a separate "Renderer" ModeOption (ESP render style) sits earlier in the option list.
    private dev.boze.api.option.ModeOption<?> findAutoMineModeOption(dev.boze.api.client.module.BaseModule mod) {
        for (dev.boze.api.option.Option<?> opt : mod.getOptions()) {
            if (opt instanceof dev.boze.api.option.ModeOption<?> modeOpt && opt.name.equalsIgnoreCase("Instant")) {
                return modeOpt;
            }
        }
        return null;
    }

    /**
     * Workaround for a real bug in ModeOption.setValueByName/getEnumClass: getEnumClass()
     * uses {@code value.getClass()}, but a Java enum constant with a body override (e.g. a
     * custom toString()) has its OWN anonymous subclass, not the real enum class --
     * getEnumConstants() on that subclass returns null, so setValueByName's search loop
     * silently finds nothing to match no matter what name is passed. This matches the
     * observed symptom exactly: the switch reliably fails specifically when the CURRENT
     * value is a constant with a body (confirmed via same-call readback verification).
     * Falls back to the constant's real enum class via getSuperclass() when this happens,
     * then sets the value directly (setValue, not setValueByName -- no getClass() involved).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean forceSetModeByName(dev.boze.api.option.ModeOption<?> modeOpt, String name) {
        Object current = modeOpt.getValue();
        if (!(current instanceof Enum<?> currentEnum)) return false;
        Class<?> cls = currentEnum.getClass();
        Object[] constants = cls.getEnumConstants();
        if (constants == null) constants = cls.getSuperclass().getEnumConstants();
        if (constants == null) return false;
        for (Object c : constants) {
            if (c instanceof Enum<?> e && e.name().equalsIgnoreCase(name)) {
                ((dev.boze.api.option.ModeOption) modeOpt).setValue(e);
                return true;
            }
        }
        return false;
    }

    // 2026-07-20: restore target comes from LiveModeCache (polls every tick all session, NOT
    // a live capture of modeOpt.getModeName() taken here) -- confirmed (reproduced with zero
    // addon modules involved) that Boze resets a ModeOption to its defaultValue the instant
    // the OWNING MODULE's enabled state flips off->on, no matter who/what triggers it, so a
    // capture taken only right here can already be stale/corrupted (e.g. from AutoMine being
    // toggled off/on earlier in the session, unrelated to BedAura) before this code ever runs.
    private void suppressAutoMineIfNeeded() {
        if (!suppressAutoMine.getValue() || autoMineSuppressed || suppressAutoMineBroken) return;
        try {
            dev.boze.api.client.module.BaseModule mod = findAutoMineModule();
            dev.boze.api.option.ModeOption<?> modeOpt = mod != null ? findAutoMineModeOption(mod) : null;
            if (modeOpt == null) { suppressAutoMineBroken = true; return; }

            if (!modeOpt.getModeName().equalsIgnoreCase(TARGET_MODE_NAME)) {
                com.example.addon.util.LiveModeCache.INSTANCE.suppressAutoMine();
                autoMineActuallyForced = true;
                forceSetModeByName(modeOpt, TARGET_MODE_NAME);
            }
            autoMineSuppressed = true;
        } catch (Exception e) {
            suppressAutoMineBroken = true;
        }
    }

    /** Restores AutoMine's "Instant" mode to LiveModeCache's last-observed real value. */
    private void restoreAutoMineIfNeeded() {
        if (!autoMineSuppressed) return;
        autoMineSuppressed = false;
        try {
            String target = com.example.addon.util.LiveModeCache.INSTANCE.getAutoMineInstantMode();
            if (target != null) {
                dev.boze.api.client.module.BaseModule mod = findAutoMineModule();
                dev.boze.api.option.ModeOption<?> modeOpt = mod != null ? findAutoMineModeOption(mod) : null;
                if (modeOpt != null) forceSetModeByName(modeOpt, target);
            }
        } catch (Exception ignored) {
        } finally {
            if (autoMineActuallyForced) {
                com.example.addon.util.LiveModeCache.INSTANCE.unsuppressAutoMine();
                autoMineActuallyForced = false;
            }
        }
    }

    /** Mirrors 0tterware/Boze-Mint-Addon's own submitRotated: bundles action+rotation into one Interaction, or a plain unrotated one when Rotate is off. */
    private void submitRotated(EventInteract event, Runnable action, float yaw, float pitch) {
        if (rotate.getValue()) {
            event.addInteraction(new Interaction(action, yaw, pitch));
        } else {
            event.addInteraction(new Interaction(action));
        }
    }


    private long placementRenderMs = 0;
    private static final long PLACEMENT_ANIM_MS = 400;
    private static final dev.boze.api.render.ClientColor RED = dev.boze.api.render.ColorMaker.staticColor(255, 40, 40);
    private net.minecraft.core.BlockPos lastRenderedPos = null;

    @EventHandler
    private void onWorldRender(dev.boze.api.event.EventWorldRender event) {
        // Yaw+action are bundled atomically in one Interaction, so the requested dir is a
        // reliable preview of real FACING even before placement. Once a bed is down,
        // placedBedPos/placedBedHeadPos take over (ground truth from findOtherBedHalf).
        net.minecraft.core.BlockPos pos;
        net.minecraft.core.BlockPos head;
        if (bedPlacedThisCycle && placedBedPos != null) {
            pos = placedBedPos;
            head = placedBedHeadPos;
            if (head == null) {
                // 2026-07-19 fix ("render có 1 nửa so với cả cái bed"): placedBedHeadPos is
                // looked up ONCE, synchronously, the instant the place packet is sent
                // (submitPlaceBed's action) -- the server's block-update for the head half
                // hasn't round-tripped back yet at that exact moment, so the lookup can return
                // null and (since it's never retried) stays null for this bed's entire
                // lifetime, rendering only one cell forever. Retry live here every frame until
                // it resolves, and cache the result back so submitDetonate's own use of
                // placedBedHeadPos benefits too, not just the render.
                head = findOtherBedHalf(Minecraft.getInstance(), pos);
                if (head != null) placedBedHeadPos = head;
            }
        } else if (currentPlacement != null) {
            pos = currentPlacement.pos();
            head = pos.relative(currentPlacement.dir());
        } else {
            lastRenderedPos = null;
            return;
        }
        if (!pos.equals(lastRenderedPos)) {
            lastRenderedPos = pos;
            placementRenderMs = System.currentTimeMillis();
        }

        float scale = 1.0f;
        if (interpolate.getValue()) {
            long elapsed = System.currentTimeMillis() - placementRenderMs;
            float t = Math.min(1.0f, elapsed / (float) PLACEMENT_ANIM_MS);
            scale = 0.15f + 0.85f * t;
        }

        // Real bed shape (vanilla BedBlock's own collision/visual shape, Block.box(0,0,0,16,9,16)):
        // full X/Z footprint but only 9/16 = 0.5625 tall, sitting on the floor -- not a full
        // 1x1x1 cube.
        final double bedHeight = 9.0 / 16.0;
        java.util.List<net.minecraft.core.BlockPos> cells = head != null
            ? java.util.List.of(pos, head) : java.util.List.of(pos);
        dev.boze.api.render.WorldDrawer.start();
        for (net.minecraft.core.BlockPos cell : cells) {
            double halfXZ = 0.5 * scale;
            double halfY = (bedHeight * 0.5) * scale;
            double cx = cell.getX() + 0.5, cy = cell.getY() + bedHeight * 0.5, cz = cell.getZ() + 0.5;
            dev.boze.api.render.WorldDrawer.box(RED, 0.25f, 0.9f,
                cx - halfXZ, cy - halfY, cz - halfXZ, cx + halfXZ, cy + halfY, cz + halfXZ);
        }
        dev.boze.api.render.WorldDrawer.draw(event.matrices);
    }


    // CraftingMenu real slot layout (verified via javap against AbstractCraftingMenu.
    // addCraftingGridSlots + CraftingMenu's own constructor, minecraft-merged-
    // 1c9175fa40-26.1.2.jar): slot 0 = result, slots 1-9 = the 3x3 grid in row-major
    // order (1,2,3 = top row; 4,5,6 = middle row; 7,8,9 = bottom row), slots 10-45 =
    // player inventory (10-36 main, 37-45 hotbar) via addStandardInventorySlots.
    // Bed recipe: 3 wool (one color) on the top row, 3 planks (one type) on the
    // middle row, bottom row empty.
    private static final int CRAFTING_RESULT_SLOT = 0;
    private static final int[] WOOL_GRID_SLOTS = {1, 2, 3};
    private static final int[] PLANK_GRID_SLOTS = {4, 5, 6};
    private static final int CRAFTING_CONTAINER_SIZE = 10; // 1 result + 9 grid

    private boolean autoCraftRunning = false;
    private int autoCraftTicks = 0;
    private boolean autoCraftBindWasDown = false;
    // Two-phase table placement: the placed block doesn't appear the same tick place() runs
    // (server round-trip), so it can't be opened synchronously. placedTablePos != null means
    // "placement sent, waiting for the block"; tableOpenSent means "opened, waiting for the screen".
    private net.minecraft.core.BlockPos placedTablePos = null;
    private boolean tableOpenSent = false;

    @EventHandler
    private void onAutoCraftBindCheck(EventTick.Post event) {
        // isBindDown polls raw GLFW key state, bypassing screen/focus routing -- typing the
        // bound letter into chat/any GUI text field still reads as "physically down", so skip
        // the raw poll entirely whenever a screen is open.
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) { autoCraftBindWasDown = false; return; }
        boolean down = isBindDown(autoCraftBind);
        // Toggle, not one-shot trigger: press once to enable persistent auto-craft-on-zero-beds
        // (reuses AutoPlace's own watcher below), press again to disable.
        if (down && !autoCraftBindWasDown) {
            boolean enabling = !autoPlace.getValue();
            autoPlace.setValue(enabling);
            dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft is " + (enabling ? "enabled" : "disabled"));
            if (enabling && mc.player != null && countBeds(mc) == 0) startAutoCraft();
        }
        autoCraftBindWasDown = down;
    }

    // Edge-triggers startAutoCraft() the moment bed count drops to zero, independent of
    // the manual keybind above (AutoPlace's description promises "automatically").
    private boolean lastHadBeds = true;

    @EventHandler
    private void onAutoCraftZeroBedsCheck(EventTick.Post event) {
        if (!autoPlace.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean hasBeds = countBeds(mc) > 0;
        if (!hasBeds && lastHadBeds) startAutoCraft();
        lastHadBeds = hasBeds;
    }

    /**
     * BindOption/Bind (dev.boze.api.option.BindOption, dev.boze.api.utility.input.Bind)
     * expose only getBind() (GLFW code) and isButton() -- no built-in "is currently
     * pressed" accessor exists in this API (verified by reading both sources in full).
     * Polling GLFW directly, same pattern already used elsewhere in this codebase
     * (EbookReader.java, GifHUD.java: GLFW.glfwGet{Key,MouseButton}(mc.getWindow().
     * handle(), code) == GLFW.GLFW_PRESS).
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

    private void startAutoCraft() {
        if (autoCraftRunning) return;
        autoCraftRunning = true;
        autoCraftTicks = 0;
        placedTablePos = null;
        tableOpenSent = false;
    }

    @EventHandler
    private void onAutoCraftTick(EventTick.Post event) {
        if (!autoCraftRunning) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { autoCraftRunning = false; return; }

        boolean screenOpen = mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
            && !(mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen);
        boolean craftingOpen = screenOpen && mc.player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu;

        autoCraftTicks++;
        if (autoCraftTicks > 200) { autoCraftRunning = false; return; } // safety timeout, mirrors EvilRekit's own break timeout

        if (!craftingOpen) {
            if (screenOpen) { autoCraftRunning = false; return; } // some OTHER container is open -- don't touch it
            if (!autoPlace.getValue()) { autoCraftRunning = false; return; } // no table open and AutoPlace is off -- nothing to do

            if (placedTablePos == null) {
                // Phase 1: place the table. The block itself won't exist until a server
                // round-trip later (integrated server included), so DON'T open it here.
                placedTablePos = tryPlaceAndOpenCraftingTable(mc);
                if (placedTablePos == null) return; // abort already handled inside (autoCraftRunning=false)
                tableOpenSent = false;
                autoCraftTicks = 0;
                return;
            }

            // Phase 2: wait for the placed block to actually become a crafting table (the
            // fix for "placed=true but block is air, isTable=false" -- that check was reading
            // synchronously, one+ ticks too early). Once it's really there, right-click to
            // open it. If it never appears, placement was genuinely rejected -> time out.
            boolean tableThere = mc.level.getBlockState(placedTablePos).getBlock()
                == net.minecraft.world.level.block.Blocks.CRAFTING_TABLE;
            if (tableThere && !tableOpenSent) {
                rotateTarget = placedTablePos.getCenter(); // face it for the open interaction
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(placedTablePos.getCenter(), Direction.UP, placedTablePos, false));
                tableOpenSent = true;
                autoCraftTicks = 0;
                return;
            }
            if (autoCraftTicks > 40) {
                dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft give up: table "
                    + (tableThere ? "opened but crafting screen never appeared" : "never appeared at " + placedTablePos + " (placement rejected)"));
                autoCraftRunning = false;
                placedTablePos = null;
                tableOpenSent = false;
            }
            return;
        }
        // screen is open -- the place/open wait is over, reset for the next cycle
        placedTablePos = null;
        tableOpenSent = false;

        net.minecraft.world.inventory.AbstractContainerMenu handler = mc.player.containerMenu;
        if (!gridHasMaterials(handler)) {
            if (!fillCraftingGrid(mc, handler)) {
                // No more wool+plank pair available -- done. closeContainer() (not
                // setScreen(null)) sends the actual close packet, or the server keeps the
                // crafting menu open and rejects every later click as a container mismatch.
                // Diagnostic dump kept short (hotbar only, 37-45) so it can't scroll off
                // Minecraft's chat history before it's readable.
                int woolSlot = findMenuSlot(handler, this::isWool);
                int plankSlot = findMenuSlot(handler, this::isPlanks);
                StringBuilder hotbarDump = new StringBuilder();
                for (int i = 37; i <= 45; i++) {
                    net.minecraft.world.item.ItemStack s = handler.getSlot(i).getItem();
                    hotbarDump.append(i).append("=").append(s.isEmpty() ? "empty" : s.getItem() + "x" + s.getCount()).append(" ");
                }
                dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft wool+plank diag: woolSlot=" + woolSlot
                    + " (" + (woolSlot == -1 ? "NOT FOUND" : handler.getSlot(woolSlot).getItem().getItem()) + ")"
                    + " plankSlot=" + plankSlot + " (" + (plankSlot == -1 ? "NOT FOUND" : handler.getSlot(plankSlot).getItem().getItem()) + ")");
                dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft hotbar (37-45): " + hotbarDump);
                dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft stop: no wool+plank pair found in inventory (closing)");
                if (autoClose.getValue()) mc.player.closeContainer();
                autoCraftRunning = false;
                return;
            }
        }
        // Shift-click the result slot to craft + collect one bed into inventory.
        mc.gameMode.handleContainerInput(handler.containerId, CRAFTING_RESULT_SLOT, 0,
            net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);

        if (isInventoryFull(mc)) {
            dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft stop: inventory full");
            if (autoClose.getValue()) mc.player.closeContainer();
            autoCraftRunning = false;
        }
    }

    private int countBeds(Minecraft mc) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof net.minecraft.world.item.BedItem) count += stack.getCount();
        }
        return count;
    }

    private boolean isInventoryFull(Minecraft mc) {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    /** True if the crafting grid already has wool in the top row and planks in the middle row. */
    private boolean gridHasMaterials(net.minecraft.world.inventory.AbstractContainerMenu handler) {
        for (int slot : WOOL_GRID_SLOTS) {
            if (!isWool(handler.getSlot(slot).getItem())) return false;
        }
        for (int slot : PLANK_GRID_SLOTS) {
            if (!isPlanks(handler.getSlot(slot).getItem())) return false;
        }
        return true;
    }

    private boolean isWool(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.WOOL);
    }

    private boolean isPlanks(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.PLANKS);
    }

    /**
     * Moves ONE wool item (any single color, whichever is found first) into all three
     * top-row grid slots, and ONE plank item into all three middle-row grid slots.
     * Returns false if no wool+plank pair is available in the player's own inventory
     * section of this menu.
     */
    private boolean fillCraftingGrid(Minecraft mc, net.minecraft.world.inventory.AbstractContainerMenu handler) {
        int woolSlot = findMenuSlot(handler, this::isWool);
        int plankSlot = findMenuSlot(handler, this::isPlanks);
        if (woolSlot == -1 || plankSlot == -1) return false;

        for (int gridSlot : WOOL_GRID_SLOTS) {
            if (!isWool(handler.getSlot(gridSlot).getItem())) {
                int slot = findMenuSlot(handler, this::isWool);
                if (slot == -1) return false;
                placeOneItem(mc, handler, slot, gridSlot);
            }
        }
        for (int gridSlot : PLANK_GRID_SLOTS) {
            if (!isPlanks(handler.getSlot(gridSlot).getItem())) {
                int slot = findMenuSlot(handler, this::isPlanks);
                if (slot == -1) return false;
                placeOneItem(mc, handler, slot, gridSlot);
            }
        }
        return true;
    }

    /**
     * Moves exactly ONE item from {@code source} to {@code dest} via right-click PICKUP --
     * left-click PICKUP dumps the WHOLE stack, wasting materials filling grid cells one at
     * a time. Right-click PICKUP on a non-empty source with an empty cursor takes HALF the
     * stack; on {@code dest} while holding an item, places exactly ONE and keeps the rest on
     * the cursor; on {@code source} again merges the remainder back.
     */
    private void placeOneItem(Minecraft mc, net.minecraft.world.inventory.AbstractContainerMenu handler, int source, int dest) {
        clickSlotRight(mc, handler, source);
        clickSlotRight(mc, handler, dest);
        clickSlotRight(mc, handler, source);
    }

    /** Left-click PICKUP a slot (pick up/place down the WHOLE stack), same primitive as EvilRekit's atomicSwap. */
    private void clickSlot(Minecraft mc, net.minecraft.world.inventory.AbstractContainerMenu handler, int slot) {
        mc.gameMode.handleContainerInput(handler.containerId, slot, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
    }

    /** Right-click PICKUP a slot: on an EMPTY cursor, picks up HALF the stack; while HOLDING an item, places exactly ONE. Vanilla single-item click. */
    private void clickSlotRight(Minecraft mc, net.minecraft.world.inventory.AbstractContainerMenu handler, int slot) {
        mc.gameMode.handleContainerInput(handler.containerId, slot, 1,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
    }

    /** Finds a menu slot (player inventory section, i.e. index >= CRAFTING_CONTAINER_SIZE) matching {@code test}. */
    private int findMenuSlot(net.minecraft.world.inventory.AbstractContainerMenu handler,
                              java.util.function.Predicate<net.minecraft.world.item.ItemStack> test) {
        for (int i = CRAFTING_CONTAINER_SIZE; i < handler.slots.size(); i++) {
            if (test.test(handler.getSlot(i).getItem())) return i;
        }
        return -1;
    }

    /**
     * Places a crafting table near the player and returns the spot it placed at (or null on
     * abort). Does NOT open it -- opening (useItemOn) must wait until the table block actually
     * exists in the world, a server round-trip later even in singleplayer (place()->getBlockState
     * on the same tick always reads air), so the caller polls for the block first.
     */
    private net.minecraft.core.BlockPos tryPlaceAndOpenCraftingTable(Minecraft mc) {
        // findInHotbar, not find: swapToSlot() -> Inventory.setSelectedSlot() only accepts a
        // hotbar index (0-8) -- a table in main inventory would crash it.
        int tableSlot = InvHelper.findInHotbar(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        if (tableSlot == -1) {
            dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft abort: no crafting table in inventory");
            autoCraftRunning = false;
            return null;
        }

        // 2026-07-18: widened from a fixed 5x5-at-player's-own-y grid to any spot within
        // the real Range slider -- the old grid missed valid spots off to the side (e.g.
        // one block up/down a slope) that were well within reach, and its below-solid/
        // above-air pair was a weaker, less accurate stand-in for the same
        // WorldHelper.isValidPlacement/PlaceHelper.isEmpty gate already proven correct for
        // bed candidates (see findBestPlacementJoint).
        net.minecraft.core.BlockPos base = mc.player.blockPosition();
        net.minecraft.core.BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        int r = (int) Math.ceil(range.getValue());
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    net.minecraft.core.BlockPos candidate = base.offset(dx, dy, dz);
                    double distSq = mc.player.distanceToSqr(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
                    if (distSq > range.getValue() * range.getValue()) continue;
                    if (!mc.level.getBlockState(candidate).canBeReplaced()) continue;
                    if (!dev.boze.api.utility.WorldHelper.isValidPlacement(candidate, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) continue;
                    if (!PlaceHelper.isEmpty(candidate)) continue;
                    if (mc.level.getBlockState(candidate.below()).canBeReplaced()) continue; // needs a real floor, no AirPlace for tables
                    // Self-overlap (2026-07-18): the player's own standing block always passed the
                    // old checks and always won on distSq=0 -- the server (even singleplayer's
                    // integrated server) rejects placing a block where a player entity currently
                    // stands, regardless of anti-cheat mode.
                    if (new net.minecraft.world.phys.AABB(candidate).intersects(mc.player.getBoundingBox())) continue;
                    if (distSq < bestDistSq) { bestDistSq = distSq; best = candidate; }
                }
            }
        }
        if (best == null) {
            dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft abort: no valid spot near " + base + " for a crafting table");
            autoCraftRunning = false;
            return null;
        }

        // Short cast(pos, airPlace, mode) overload defaults to wallsRange=0.0 and
        // strictDirection=true -- same root cause already fixed in placeBed's own
        // cast() call (2026-07-16): strict+zero-tolerance means the raycast only
        // succeeds when the camera happens to be at just the right geometric angle,
        // which is why placing the table "phải nhảy lên mới đặt được" (jumping
        // changes eye height enough to accidentally satisfy the strict check). Real
        // range/wallsRange + non-strict direction removes that dependency entirely.
        // Table has a floor below (candidate gate above required it), so build the hit by
        // clicking the top face of that floor block -- the same non-airPlace formulation
        // placeBedJoint uses. cast() isn't needed here (there's always a real support).
        net.minecraft.core.BlockPos support = best.below();
        net.minecraft.world.phys.BlockHitResult hit = new BlockHitResult(
            new Vec3(best.getX() + 0.5, best.getY(), best.getZ() + 0.5), Direction.UP, support, false);
        rotateTarget = hit.getLocation();

        InvHelper.swapToSlot(tableSlot, SwapType.Normal);
        boolean placed = PlaceHelper.place(placeMode.getValue(), hit, InteractionHand.MAIN_HAND);
        InvHelper.swapBack();
        dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "AutoCraft placeTable at " + best + " -> " + placed
            + " (waiting for block to appear before opening)");
        return best;
    }

    /**
     * Computes the placement hit at {@code pos} only -- no side effects, no swapping, no
     * placing. 2026-07-19 rewrite: ported directly from the REAL Mint source
     * (0tterware/Boze-Mint-Addon's AutoBedFeature.getHitResult/placeBed, read from
     * Mint-master/src/main/java/net/melbourne/modules/impl/combat/AutoBedFeature.java) --
     * previous versions routed AirPlace through Boze's own {@code PlaceHelper.cast(pos, true,
     * ...)}, which kept returning null for every candidate once they scattered across a wide
     * search cube with no nearby solid anchor (user repro: FakePlayer submerged in lava,
     * every candidate failed regardless of blacklisting/aim-correction attempts). Mint's real
     * AirPlace never calls anything like cast() at all for the fallback case: it searches its
     * OWN 6 neighbor directions for a real solid face first (getHitResult), and if genuinely
     * none exists, fabricates a bare, UNVALIDATED {@code BlockHitResult} pointing at {@code pos}
     * itself and sends it straight to the server -- no internal engine validation to reject it.
     * Replicated verbatim below; only the strictDirection check is real Mint logic too (a plain
     * eye-position-vs-face-normal dot product, NOT an aim/look-direction check -- confirms the
     * earlier "stale current rotation" theory was chasing the wrong mechanism).
     */
    private BlockHitResult computeBedHit(Minecraft mc, net.minecraft.core.BlockPos pos) {
        net.minecraft.core.BlockPos below = pos.below();
        if (!mc.level.getBlockState(below).canBeReplaced()) {
            // Real floor -- click its top face directly (Mint's getHitResult, same branch).
            return new BlockHitResult(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), Direction.UP, below, false);
        }
        if (!airPlace.getValue()) {
            dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "placeBed abort: no floor below " + pos + " and AirPlace is off");
            return null;
        }

        // No floor -- search all neighbors (except DOWN, already covered above) for a real
        // solid face to click, exactly like Mint's getHitResult loop.
        Vec3 eyes = mc.player.getEyePosition();
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            net.minecraft.core.BlockPos neighbor = pos.relative(dir);
            var state = mc.level.getBlockState(neighbor);
            if (state.isAir() || state.canBeReplaced()) continue;

            Direction side = dir.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(pos).add(dir.getStepX() * 0.5, dir.getStepY() * 0.5, dir.getStepZ() * 0.5);
            if (strictDirection.getValue()) {
                // Mint's real check: is the eye on the correct side of this face's outward
                // normal? Purely positional (eye location vs. face), nothing to do with the
                // player's current look direction -- unlike the aim-based theory tried
                // earlier, this can never depend on stale rotation.
                Vec3 eyeToHit = hitVec.subtract(eyes);
                Vec3 sideVec = new Vec3(side.getStepX(), side.getStepY(), side.getStepZ());
                if (eyeToHit.dot(sideVec) >= 0) continue;
            }
            return new BlockHitResult(hitVec, side, neighbor, false);
        }

        // No real neighbor anywhere around pos -- Mint's own dummy AirPlace fallback: a bare,
        // unvalidated hit at pos itself. This is what actually lets a bed go down when
        // floating in the middle of a lava lake with no solid block within reach at all.
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    /**
     * Swaps to a bed and places it using an ALREADY-COMPUTED {@code hit} (from
     * {@link #computeBedHit}, evaluated once in {@link #submitPlaceBed} before the rotated
     * Interaction was submitted). Returns the real place() result (verified against actual
     * post-place block state, not just trusted).
     */
    private boolean executePlaceBed(Minecraft mc, net.minecraft.core.BlockPos pos, BlockHitResult hit) {
        // Mirrors 0tterware/Boze-Mint-Addon's findBedSlot EXACTLY (2026-07-18 revert+fix,
        // "để bed trong inventory (không hotbar), Switch=Alt báo no bed in hotbar" -- the
        // earlier blanket findInHotbar switch was wrong for Alt specifically. Alt swap
        // doesn't go through Inventory.setSelectedSlot() at all (that's what made Normal/
        // Silent crash on a non-hotbar slot in the first place), so Alt can legitimately
        // search the WHOLE inventory, same as Mint's own `swapMode == Alt ? find : findInHotbar`.
        int bedSlot = swapMode.getValue() == SwapType.Alt
            ? InvHelper.find(stack -> !stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BedItem)
            : InvHelper.findInHotbar(stack -> !stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BedItem);
        if (bedSlot == -1) {
            if (!lastLoggedNoBedSlot) {
                dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "placeBed abort: no bed found ("
                    + (swapMode.getValue() == SwapType.Alt ? "searched whole inventory" : "searched HOTBAR only -- "
                        + "bed is likely in main inventory; use Alt swap mode or move it to the hotbar") + ")");
                lastLoggedNoBedSlot = true;
            }
            return false;
        }
        lastLoggedNoBedSlot = false;

        lastPlacedAnchorPos = pos;

        // Mirrors 0tterware/Boze-Mint-Addon's performStep EXACTLY (2026-07-18 rewrite,
        // "thi thoảng dù chọn Silent Switch, nó vẫn đặt cục obsidian"): two real divergences
        // found from Mint's actual source, neither a Silent-vs-Normal semantic choice --
        // (1) Normal mode in Mint NEVER calls swapBack() at all (the swapped item stays
        // visibly in hand -- that's the whole point of "Normal"); our old code swapped back
        // unconditionally for every mode. (2) Silent/Alt in Mint sends an extra
        // ServerboundSetCarriedItemPacket resync BEFORE acting whenever swapToSlot itself
        // reports failure -- forces the server's belief about the selected slot back in
        // sync with the client's real one before the place() packet goes out. Missing that
        // resync is exactly the kind of intermittent desync that would occasionally place
        // whatever the server still thought was selected (e.g. obsidian) instead of the bed.
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> heldOk =
            s -> !s.isEmpty() && s.getItem() instanceof net.minecraft.world.item.BedItem;
        boolean placed;
        if (swapMode.getValue() == SwapType.Normal) {
            InvHelper.swapToSlot(bedSlot, SwapType.Normal);
            placed = PlaceHelper.place(placeMode.getValue(), hit, InteractionHand.MAIN_HAND);
        } else {
            boolean swapped = InvHelper.swapToSlot(bedSlot, swapMode.getValue());
            if (!swapped) {
                if (!heldOk.test(mc.player.getMainHandItem())) {
                    dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "placeBed abort: swap to bed failed ("
                        + swapMode.getValue() + " returned false) and a bed isn't already in hand");
                    return false;
                }
                if (mc.getConnection() != null) {
                    mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(
                        mc.player.getInventory().getSelectedSlot()));
                }
            }
            try {
                placed = PlaceHelper.place(placeMode.getValue(), hit, InteractionHand.MAIN_HAND);
            } finally {
                if (swapped) InvHelper.swapBack();
            }
        }

        return placed;
    }
}
