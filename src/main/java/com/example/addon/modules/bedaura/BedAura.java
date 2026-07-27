package com.example.addon.modules.bedaura;

import dev.boze.api.addon.AddonModule;
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

    // Temporary diagnostic (2026-07-25): logs searchPlacement's winning candidate every time
    // it changes, to get real numbers instead of guessing at why some spots pick a far/floating
    // cell. Remove once root-caused.
    public final ToggleOption debugSearch = new ToggleOption(this, "DebugSearch",
        "Log the winning search candidate to chat every recompute.", false);

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

    // Semantic fix (was scaling extrapolation by this slider's raw 0-10 value, e.g. "6 ticks
    // ahead" -- Mint's real Predict is a plain on/off boolean that adds exactly ONE step of
    // current velocity when enabled, never scaled by any tick count). Kept as an existing
    // SliderOption (no new toggle) but now only read as on/off via predictOn() below -- 0 = off,
    // any nonzero = on, matching Mint's actual formula exactly regardless of the slider's value.
    public final SliderOption predict = new SliderOption(this, "Predict",
        "Extrapolate the target's position by one step of its current velocity when enabled (on/off, not scaled by this value).",
        2.0, 0.0, 10.0, 1.0);

    /** predict's slider value as Mint's real on/off semantic -- see predict's own doc for why. */
    private double predictOn() {
        return predict.getValue() > 0 ? 1.0 : 0.0;
    }

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
        lastLoggedNoBed = false;
        lastLoggedNoBedSlot = false;
        clearSelection();
        recomputeTicks = 0;
    }

    // Decide (search) and act (place/detonate) run from the SAME onInteract cadence below.
    //
    // 2026-07-25 (user request): dropped the sticky-lock model (lockFoot/lockDir/lockTarget/
    // lockValid) in favor of periodically recomputing the target+placement fresh, same idiom
    // OvaqPA's calculateStage used -- pick a target, act on it, done, no persisted "is this
    // still the right choice" validity check across many ticks. A cheap per-tick re-check
    // (currentSelectionStillGood) still runs BETWEEN scheduled recomputes so a target that
    // drifts out of the current pick's viable range gets caught immediately instead of waiting
    // out the rest of the window.
    private static final int RECOMPUTE_INTERVAL_TICKS = 4;
    private int recomputeTicks = 0;

    private net.minecraft.core.BlockPos currentFoot = null;
    private Direction currentDir = null;
    private Player currentTarget = null;
    private float currentEstimatedDamage = 0f;
    // Adopt mode: an ALREADY-PLACED bed (anyone's) found still dealing damage to a target,
    // detonated directly instead of placing a new one. currentHead is its other half (ground
    // truth via findOtherBedHalf); currentDir is meaningless here (nothing was placed) so it
    // stays null.
    private net.minecraft.core.BlockPos currentHead = null;
    private boolean currentAdopt = false;

    private void clearSelection() {
        currentFoot = null;
        currentDir = null;
        currentTarget = null;
        currentEstimatedDamage = 0f;
        currentHead = null;
        currentAdopt = false;
    }

    /**
     * Cheap per-tick re-validation of the CURRENT (not-yet-placed) currentFoot/currentDir
     * against the target's LIVE position -- same damage/reach math searchPlacement used to pick
     * it, just for one candidate instead of the whole cube. False if currentFoot/currentDir/
     * currentTarget aren't set yet (nothing to validate).
     */
    private boolean currentSelectionStillGood(Minecraft mc) {
        if (currentFoot == null || currentDir == null || currentTarget == null) return false;
        if (!currentTarget.isAlive() || currentTarget.getHealth() <= 0) return false;
        net.minecraft.core.BlockPos head = currentFoot.relative(currentDir);
        double exReach = Math.max(range.getValue(), wallsRange.getValue());
        if (!withinReach(mc, currentFoot, exReach) && !withinReach(mc, head, exReach)) return false;

        Vec3 footCenter = currentFoot.getCenter(), headCenter = head.getCenter();
        float dFoot = DamageUtils.estimateHpLoss(footCenter, currentTarget, predictOn());
        float dHead = DamageUtils.estimateHpLoss(headCenter, currentTarget, predictOn());
        float dmg = Math.max(dFoot, dHead);
        if (dmg < minDamage.getValue()) return false;

        Vec3 detonateCenter = dHead >= dFoot ? headCenter : footCenter;
        float selfDmg = DamageUtils.estimateHpLoss(detonateCenter, mc.player, predictOn());
        if (selfDmg > maxDamage.getValue()) return false;

        currentEstimatedDamage = dmg;
        return true;
    }

    private boolean isBedBlock(net.minecraft.core.BlockPos pos) {
        return Minecraft.getInstance().level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    private record ExistingBed(net.minecraft.core.BlockPos pos, Player target, float damage) {}

    /**
     * Scans for an ALREADY-PLACED bed (anyone's -- own leftover, an enemy's, whatever) still
     * dealing real damage to a tracked target, so a recompute can adopt-and-detonate it
     * directly instead of always placing a brand new bed even when a perfectly good one is
     * sitting right there. Same search cube as searchPlacement, same reach/damage gates, plus
     * a "within 6 blocks of the target's hitbox center" sanity bound (a bed 6+ blocks from the
     * target it's being matched against is almost certainly someone else's unrelated bed).
     */
    private ExistingBed findExistingBed(Minecraft mc) {
        double exReach = Math.max(range.getValue(), wallsRange.getValue());
        int r = (int) Math.ceil(BED_RANGE);
        ExistingBed best = null;

        for (Player targetPlayer : targets) {
            net.minecraft.core.BlockPos center = targetPlayer.blockPosition();
            Vec3 targetCenter = targetPlayer.getBoundingBox().getCenter();

            // Same exact interval-intersection prune as searchPlacement -- see its comment.
            double reachEye = exReach + 0.5;
            Vec3 eye = mc.player.getEyePosition();
            int dxLo = Math.max(-r, (int) Math.floor(eye.x - reachEye - center.getX()));
            int dxHi = Math.min(r, (int) Math.ceil(eye.x + reachEye - center.getX()));
            if (dxLo > dxHi) continue;
            int dyLo = Math.max(-r, (int) Math.floor(eye.y - reachEye - center.getY()));
            int dyHi = Math.min(r, (int) Math.ceil(eye.y + reachEye - center.getY()));
            if (dyLo > dyHi) continue;
            int dzLo = Math.max(-r, (int) Math.floor(eye.z - reachEye - center.getZ()));
            int dzHi = Math.min(r, (int) Math.ceil(eye.z + reachEye - center.getZ()));
            if (dzLo > dzHi) continue;

            for (int dx = dxLo; dx <= dxHi; dx++) {
                for (int dy = dyLo; dy <= dyHi; dy++) {
                    for (int dz = dzLo; dz <= dzHi; dz++) {
                        net.minecraft.core.BlockPos pos = center.offset(dx, dy, dz);
                        if (!isBedBlock(pos)) continue;
                        if (pos.getCenter().distanceTo(targetCenter) > 6.0) continue;
                        if (!withinReach(mc, pos, exReach)) continue;

                        float selfDmg = DamageUtils.estimateHpLoss(pos.getCenter(), mc.player, predictOn());
                        if (selfDmg > maxDamage.getValue()) continue;
                        float dmg = DamageUtils.estimateHpLoss(pos.getCenter(), targetPlayer, predictOn());
                        if (dmg < minDamage.getValue()) continue;

                        if (best == null || dmg > best.damage()) best = new ExistingBed(pos, targetPlayer, dmg);
                    }
                }
            }
        }
        return best;
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

    /**
     * Single synchronous full sweep, matching Mint's own findPlace() exactly (fetched real
     * source, github.com/0tterware/Boze-Mint-Addon BedAuraModule.findPlace) -- every (candidate,
     * target) pair across the whole search cube, keeping whichever single combination scores
     * best overall. No chunking/budget-splitting: Mint doesn't do that at all, it relies purely
     * on the sticky-lock gate (runBedCycle only calls this when lockValid() is false) plus a
     * tick-count cooldown after a failed search to bound how often the full O(r^3) cost runs.
     */
    private JointPlacement searchPlacement(Minecraft mc) {
        int r = (int) Math.ceil(BED_RANGE);
        double minD = minDamage.getValue(), maxD = maxDamage.getValue();
        double exReach = Math.max(range.getValue(), wallsRange.getValue());
        // No longer restricted by Rotate -- submitRotated always forces the real yaw/pitch
        // for bed place/detonate now (see its javadoc), so every direction is genuinely
        // reachable regardless of the Rotate toggle.
        Direction only = null;

        JointPlacement best = null;
        double bestScore = -1.0;

        for (Player targetPlayer : targets) {
            // Raw current blockPosition(), never predicted -- predict only feeds the damage
            // estimate below, moving the search cube itself would drag it off a moving target.
            net.minecraft.core.BlockPos center = targetPlayer.blockPosition();
            Vec3 targetCenter = targetPlayer.getBoundingBox().getCenter();

            // Search-cost pruning, applies to every AC mode the same way (user request:
            // "PlaceMode không ảnh hưởng, đừng lôi ac handler vào") -- what candidates WE choose
            // to spend cycles evaluating doesn't change what the server/AC ultimately accepts,
            // so there's no reason to special-case Grim here.
            Vec3 eye = mc.player.getEyePosition();
            double reachEye = exReach + 0.5;
            // Exact worst-case Euclidean bound BEFORE the (necessary-but-not-sufficient)
            // per-axis check below: any cell inside the BED_RANGE cube is at most r*sqrt(3)
            // from the target's own block center (cube half-diagonal). This catches the case
            // the per-axis check alone misses -- a target far away DIAGONALLY can still have
            // each individual axis's span overlap the reach box even though the true 3D
            // distance is well out of reach (reproduced: user standing across a ravine, each
            // axis "close enough" alone, real distance isn't) -- axis-only bounding is a
            // looser, necessary-but-not-sufficient test.
            if (center.getCenter().distanceTo(eye) > r * Math.sqrt(3) + reachEye) continue;
            int dxLo = Math.max(-r, (int) Math.floor(eye.x - reachEye - center.getX()));
            int dxHi = Math.min(r, (int) Math.ceil(eye.x + reachEye - center.getX()));
            if (dxLo > dxHi) continue;
            int dyLo = Math.max(-r, (int) Math.floor(eye.y - reachEye - center.getY()));
            int dyHi = Math.min(r, (int) Math.ceil(eye.y + reachEye - center.getY()));
            if (dyLo > dyHi) continue;
            int dzLo = Math.max(-r, (int) Math.floor(eye.z - reachEye - center.getZ()));
            int dzHi = Math.min(r, (int) Math.ceil(eye.z + reachEye - center.getZ()));
            if (dzLo > dzHi) continue;

            for (int dx = dxLo; dx <= dxHi; dx++) {
                for (int dy = dyLo; dy <= dyHi; dy++) {
                    for (int dz = dzLo; dz <= dzHi; dz++) {
                        net.minecraft.core.BlockPos foot = center.offset(dx, dy, dz);
                        // Search-cost only, same for every mode: dxLo/dxHi etc bound an
                        // axis-ALIGNED BOX, not the true reach SPHERE -- footPlaceable's own
                        // withinReach() would normally trim the box's corners down to the
                        // sphere for non-Grim modes, but withinReach() unconditionally returns
                        // true under Grim (no client-side reach concept there by design), so
                        // without this the corner cells that only NCP/other modes would prune
                        // still go all the way through to the expensive damage/exposure calc
                        // below under Grim -- real root cause of "FPS fine NCP, bad Grim, same
                        // scene". Not an extra Grim rule -- just doing here, once, the same real
                        // distance check every mode ends up doing anyway.
                        if (foot.getCenter().distanceTo(eye) > reachEye) continue;
                        if (!footPlaceable(mc, foot, exReach)) continue;

                        for (Direction dir : Direction.Plane.HORIZONTAL) {
                            if (only != null && dir != only) continue;
                            net.minecraft.core.BlockPos head = foot.relative(dir);
                            if (!headPlaceable(mc, head)) continue;

                            Vec3 footCenter = foot.getCenter();
                            Vec3 headCenter = head.getCenter();
                            float dFootTarget = DamageUtils.estimateHpLoss(footCenter, targetPlayer, predictOn());
                            float dHeadTarget = DamageUtils.estimateHpLoss(headCenter, targetPlayer, predictOn());
                            boolean useHead = dHeadTarget >= dFootTarget;
                            Vec3 detonateCenter = useHead ? headCenter : footCenter;
                            float dmg = Math.max(dFootTarget, dHeadTarget);
                            if (dmg < minD) continue;

                            float selfDmg = DamageUtils.estimateHpLoss(detonateCenter, mc.player, predictOn());
                            if (selfDmg > maxD) continue;

                            // Exact Mint scoring (findPlace): small tie-break toward the head
                            // landing closer to the target's hitbox CENTER, strict ">" otherwise.
                            double score = dmg;
                            if (headCenter.distanceTo(targetCenter) < footCenter.distanceTo(targetCenter)) score += 0.01;

                            if (best == null || score > bestScore) {
                                best = new JointPlacement(foot, dir, targetPlayer, dmg);
                                bestScore = score;
                            }
                        }
                    }
                }
            }
        }
        if (best != null && debugSearch.getValue()) {
            net.minecraft.core.BlockPos t = best.target().blockPosition();
            dev.boze.api.utility.ChatHelper.sendMsg("BedAura",
                "best foot=" + best.foot() + " dir=" + best.dir() + " dmg=" + best.estimatedDamage()
                    + " targetPos=" + t + " playerEye=" + mc.player.getEyePosition()
                    + " dist(footCenter,eye)=" + best.foot().getCenter().distanceTo(mc.player.getEyePosition()));
        }
        return best;
    }

    /**
     * Exact 1:1 port of Mint's footPlaceable (BedAuraModule). Critically does NOT reject a
     * candidate whose cell overlaps the LOCAL player's own hitbox -- an earlier extra
     * `AABB(foot).intersects(mc.player.getBoundingBox())` check here was the real cause of
     * "BedAura đặt sai bét nhè / đứng chéo phải bị, chéo trái không đúng": bed-clutch routinely
     * places the bed right at your own feet, and whether the local player's hitbox happened to
     * overlap the optimal candidate cell depended entirely on which side of the target you were
     * standing -- so the best placement got silently rejected from one stance and kept from the
     * other. Mint has no such check; a bed placing into the player's own space is fine.
     */
    private boolean footPlaceable(Minecraft mc, net.minecraft.core.BlockPos foot, double reach) {
        if (!withinReach(mc, foot, reach)) return false;
        if (!dev.boze.api.utility.WorldHelper.isInWorldBounds(foot) || !dev.boze.api.utility.WorldHelper.isRegionLoaded(foot)) return false;
        if (!dev.boze.api.utility.WorldHelper.isReplaceable(foot)) return false;
        if (!dev.boze.api.utility.WorldHelper.canPlaceAt(foot)) return false;
        if (!dev.boze.api.utility.WorldHelper.isValidPlacement(foot, net.minecraft.world.level.block.Blocks.WHITE_BED)) return false;
        if (!PlaceHelper.isEmpty(foot)) return false;
        if (!airPlace.getValue() && dev.boze.api.utility.WorldHelper.isReplaceable(foot.below())) return false; // need a floor
        return true;
    }

    /**
     * Exact 1:1 port of Mint's headPlaceable. placeOnFeet == Mint's IntoTarget: when on, the
     * head half is allowed to land inside a target entity's hitbox (max damage) with only the
     * isReplaceable check. When off, the head must additionally be empty and free of ANY entity
     * (entityAt checks all living entities, matching Mint -- not just the current target).
     */
    private boolean headPlaceable(Minecraft mc, net.minecraft.core.BlockPos head) {
        if (!dev.boze.api.utility.WorldHelper.isReplaceable(head)) return false;
        if (placeOnFeet.getValue()) return true;
        if (!PlaceHelper.isEmpty(head)) return false;
        return !entityAt(mc, head);
    }

    /** True if any non-item living entity (except the local player) overlaps {@code pos}. Mirrors Mint's entityAt. */
    private boolean entityAt(Minecraft mc, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos);
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (e instanceof net.minecraft.world.entity.item.ItemEntity) continue;
            if (!e.isAlive()) continue;
            if (e.getBoundingBox().intersects(box)) return true;
        }
        return false;
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
        if (targets.isEmpty()) { currentPlacement = null; rotateTarget = null; clearSelection(); return; }

        // Full O(r^3) search only when the CURRENT pick actually stops being good (target moved
        // Recompute the target+placement fresh every RECOMPUTE_INTERVAL_TICKS onInteract calls
        // instead of validating an old sticky lock -- see this field's doc above.
        if (recomputeTicks <= 0) {
            recomputeTicks = RECOMPUTE_INTERVAL_TICKS;

            // Prefer adopting an already-placed bed (anyone's) that's still damaging a target
            // over always placing a brand new one.
            ExistingBed existing = findExistingBed(mc);
            if (existing != null) {
                currentFoot = existing.pos();
                currentHead = findOtherBedHalf(mc, existing.pos());
                currentDir = null;
                currentTarget = existing.target();
                currentAdopt = true;
                currentEstimatedDamage = existing.damage();
                bedPlacedThisCycle = true;
                placedBedPos = currentFoot;
                placedBedHeadPos = currentHead;
                currentPlacement = null; // adopted bed isn't a NEW placement -- nothing to preview
                submitDetonate(event, mc);
                return;
            }

            JointPlacement jointPlacement = searchPlacement(mc);
            if (jointPlacement == null) {
                clearSelection(); currentPlacement = null; rotateTarget = null; return;
            }
            currentFoot = jointPlacement.foot();
            currentDir = jointPlacement.dir();
            currentTarget = jointPlacement.target();
            currentEstimatedDamage = jointPlacement.estimatedDamage();
            currentAdopt = false;
            currentHead = null;
        } else {
            recomputeTicks--;
            // Cheap re-check every tick BETWEEN scheduled full sweeps: currentFoot is a fixed
            // absolute BlockPos picked up to RECOMPUTE_INTERVAL_TICKS ago against wherever the
            // target WAS then -- catch it drifting out of viable range immediately instead of
            // waiting out the rest of the window ("đặt lên trời").
            if (currentFoot != null && !currentAdopt && !currentSelectionStillGood(mc)) {
                recomputeTicks = 0;
            }
        }

        if (currentFoot == null || currentDir == null || currentTarget == null) {
            currentPlacement = null; rotateTarget = null; return;
        }

        currentPlacement = new PlacementCandidate(currentFoot, currentDir, currentEstimatedDamage);
        submitPlaceBed(event, mc, currentFoot, currentDir);
    }

    private void submitPlaceBed(EventInteract event, Minecraft mc, net.minecraft.core.BlockPos pos, Direction dir) {
        BlockHitResult hit = computeBedHit(mc, pos);
        // Force an immediate re-recompute (instead of waiting out the rest of the 20-tick
        // window) on a failed hit -- without this a placement that keeps failing (e.g.
        // AirPlace toggled off after this spot was picked while floating) would retry the
        // same dead BlockPos until the next scheduled recompute instead of picking a new one.
        if (hit == null) { clearSelection(); recomputeTicks = 0; return; }
        float yaw = dir.toYRot();
        float pitch = dev.boze.api.utility.MathHelper.calculateRotation(mc.player.getEyePosition(), hit.getLocation())[1];
        Runnable action = () -> {
            if (executePlaceBed(mc, pos, hit)) {
                bedPlacedThisCycle = true;
                placedBedPos = lastPlacedAnchorPos;
                placedBedHeadPos = findOtherBedHalf(mc, lastPlacedAnchorPos);
                lastActionMs = System.currentTimeMillis();
            }
        };
        submitRotated(event, action, yaw, pitch);
    }

    private void submitDetonate(EventInteract event, Minecraft mc) {
        if (placedBedPos == null) return;
        net.minecraft.core.BlockPos target = placedBedPos;
        if (placedBedHeadPos != null && currentTarget != null) {
            // Detonate whichever bed half deals MORE damage to the target (estimateHpLoss),
            // not whichever is merely distance-closer -- with terrain occlusion the closer
            // half isn't always the higher-damage one.
            float dAnchor = DamageUtils.estimateHpLoss(placedBedPos.getCenter(), currentTarget, predictOn());
            float dHead = DamageUtils.estimateHpLoss(placedBedHeadPos.getCenter(), currentTarget, predictOn());
            if (dHead > dAnchor) target = placedBedHeadPos;
        }
        net.minecraft.core.BlockPos finalTarget = target;
        BlockHitResult hit = computeBedFace(mc, target);
        if (hit == null) {
            // computeBedFace only returns null when EVERY one of the 6 faces is either
            // back-face-culled or outside real `reach` (its fallback needs nothing but reach,
            // no raycast-clear requirement) -- a stable geometric fact about this exact bed
            // position, not a one-tick flake. Genuinely unreachable (e.g. the search ran once
            // with unstable geometry mid-air and landed a real bed disconnected from the
            // target), so give up on THIS bed immediately instead of leaving bedPlacedThisCycle
            // latched forever -- runBedCycle's top-of-cycle check routes straight to
            // submitDetonate whenever that flag is true, which otherwise permanently blocks the
            // recompute branch below it until a manual toggle resets the flag (matches report:
            // "chạm đất rồi vẫn không đặt lại đúng, phải tắt đi bật lại").
            bedPlacedThisCycle = false;
            placedBedPos = null;
            placedBedHeadPos = null;
            currentPlacement = null;
            recomputeTicks = 0;
            return;
        }
        // Swap to a non-bed, non-block item before right-clicking the bed -- with a
        // block/bed still in hand, right-clicking a block face can place THAT item instead
        // of interacting with the bed. findDetonateSlot below mirrors Mint's own preference
        // order (real item > empty slot > any non-bed slot).
        int detonateSlot = findDetonateSlot(mc);
        float[] rot = dev.boze.api.utility.MathHelper.calculateRotation(mc.player.getEyePosition(), hit.getLocation());
        Runnable action = () -> {
            boolean swapped = detonateSlot != Integer.MIN_VALUE && InvHelper.swapToSlot(detonateSlot, swapMode.getValue());
            try {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            } finally {
                if (swapped) InvHelper.swapBack();
            }
            bedPlacedThisCycle = false;
            placedBedPos = null;
            placedBedHeadPos = null;
            currentPlacement = null;
            lastActionMs = System.currentTimeMillis();
        };
        submitRotated(event, action, rot[0], rot[1]);
    }

    /**
     * Real face-finding raycast for detonating a placed bed, replacing a hardcoded
     * `Direction.UP` hit that ignored actual approach geometry entirely (broken whenever the
     * top face wasn't the one actually reachable/visible -- likely contributor to detonate
     * misfires reading as "đặt sai"). For each of the 6 faces: back-face cull (only consider
     * faces the eye is actually outside of), reach-gate (skipped for Grim, same as
     * withinReach), then a real raycast eye->face -- a face whose ray isn't blocked by some
     * OTHER block first is preferred (closest such face wins); if every face is raycast-
     * blocked, falls back to the closest reachable face on plain distance so this never just
     * gives up on a target with a bit of clutter nearby.
     */
    private BlockHitResult computeBedFace(Minecraft mc, net.minecraft.core.BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        boolean grim = placeMode.getValue() == InteractionMode.Grim;
        double reach = Math.max(range.getValue(), wallsRange.getValue());
        Vec3 center = pos.getCenter();

        BlockHitResult best = null;
        double bestDist = Double.MAX_VALUE;
        BlockHitResult fallback = null;
        double fallbackDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            Vec3 normal = dir.getUnitVec3();
            Vec3 face = center.add(normal.scale(0.5));
            if (eye.subtract(face).dot(normal) <= 0) continue; // back-face cull

            double dist = face.distanceTo(eye);
            if (!grim && dist > reach) continue;

            if (dist < fallbackDist) {
                fallbackDist = dist;
                fallback = new BlockHitResult(face, dir, pos, false);
            }

            // Boze's WorldHelper.raycast never returns null (clip() reports Type.MISS instead
            // of null on a miss -- see reference_boze_raycast_never_null) so this only needs
            // to check the TYPE, not null-guard the result itself.
            net.minecraft.world.phys.HitResult rc = dev.boze.api.utility.WorldHelper.raycast(eye, face);
            if (rc instanceof BlockHitResult bhr && bhr.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    && !bhr.getBlockPos().equals(pos)) {
                continue; // some other block sits between the eye and this face
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = new BlockHitResult(face, dir, pos, false);
            }
        }
        return best != null ? best : fallback;
    }

    /**
     * Slot to hold while detonating: prefers a real non-bed/non-block item (so the click can
     * never accidentally place something instead of interacting with the bed), then an empty
     * slot, then any non-bed slot as a last resort. Alt swap searches the whole inventory
     * (matches findBedSlot's own Alt-vs-hotbar split in executePlaceBed); every other swap
     * mode searches the hotbar only, since only a hotbar index is a legal carried-item slot.
     * Integer.MIN_VALUE means "nothing safe to swap to" -- submitDetonate's caller treats that
     * as "don't swap, just interact with whatever's already in hand".
     */
    private int findDetonateSlot(Minecraft mc) {
        boolean wholeInv = swapMode.getValue() == SwapType.Alt;
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> good = s ->
            !s.isEmpty() && !(s.getItem() instanceof net.minecraft.world.item.BedItem)
                && !(s.getItem() instanceof net.minecraft.world.item.BlockItem);
        int slot = wholeInv ? InvHelper.find(good) : InvHelper.findInHotbar(good);
        if (slot != -1) return slot;

        int empty = InvHelper.findInHotbar(net.minecraft.world.item.ItemStack::isEmpty);
        if (empty != -1) return empty;

        java.util.function.Predicate<net.minecraft.world.item.ItemStack> notBed = s ->
            !s.isEmpty() && !(s.getItem() instanceof net.minecraft.world.item.BedItem);
        int any = wholeInv ? InvHelper.find(notBed) : InvHelper.findInHotbar(notBed);
        return any != -1 ? any : Integer.MIN_VALUE;
    }

    /** Mirrors 0tterware/Boze-Mint-Addon's own submitRotated: bundles action+rotation into one Interaction, or a plain unrotated one when Rotate is off. */
    // Always force real yaw/pitch for bed place/detonate -- Interaction's own doc confirms the
    // no-rotation constructor fires the action at WHATEVER the player's real facing happens to
    // be that instant, with zero forced correction. A bed's correct facing (head dí vào hole)
    // is a property of the SEARCH result, not of momentary crosshair aim -- gating this on the
    // Rotate toggle made placement direction drift with the player's live look direction between
    // recomputes even for a fully static target (root cause of "đặt lên trời" that only
    // self-corrected on re-enable, when the player happened to be staring straight at the
    // target again). Rotate now only matters for whether searchPlacement itself needs to
    // restrict to a single direction, which it no longer does (see `only` below).
    private void submitRotated(EventInteract event, Runnable action, float yaw, float pitch) {
        event.addInteraction(new Interaction(action, yaw, pitch));
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
     * placing. 2026-07-25 rewrite: the "6-neighbor search + fabricate unvalidated hit at pos"
     * version this replaced was NOT actually what Mint does -- fetched the REAL current Mint
     * source (github.com/0tterware/Boze-Mint-Addon, BedAuraModule.computePlaceHit) and its
     * AirPlace branch just calls {@code PlaceHelper.cast(pos, true, mode, range, wallsRange,
     * strictDirection)} directly, no manual neighbor loop at all. The earlier "kept returning
     * null" problem that motivated the hand-rolled replacement was from calling cast()'s
     * SHORT overload (defaults range=4.5/wallsRange=0.0, ignoring the user's real Range/
     * WallsRange sliders -- same mistake documented in tryPlaceAndOpenCraftingTable's own
     * comment) -- not a flaw in cast() itself. The FULL 6-arg overload (verified against
     * PlaceHelper.java in the Boze API sources jar) is a real engine-level raycast that
     * handles the floor/no-floor/air cases correctly on its own.
     * <p>
     * Concrete bug this fixes (user repro, 2026-07-25): the old manual neighbor search picked
     * whichever of the 5 non-DOWN directions it hit FIRST in Direction.values() iteration
     * order -- for a candidate with an incomplete/asymmetric set of solid neighbors (e.g. a
     * floating spot missing a block on one side), that could be an arbitrary, wrong-looking
     * face instead of the one a real player would expect, and adding a block on the "missing"
     * side visibly changed which face won purely by changing iteration outcomes. cast()
     * doesn't have this failure mode -- it's the same real raycast Mint itself relies on.
     */
    private BlockHitResult computeBedHit(Minecraft mc, net.minecraft.core.BlockPos pos) {
        if (airPlace.getValue()) {
            return PlaceHelper.cast(pos, true, placeMode.getValue(),
                range.getValue(), wallsRange.getValue(), strictDirection.getValue());
        }
        net.minecraft.core.BlockPos support = pos.below();
        if (dev.boze.api.utility.WorldHelper.isReplaceable(support)) {
            dev.boze.api.utility.ChatHelper.sendMsg("BedAura", "placeBed abort: no floor below " + pos + " and AirPlace is off");
            return null;
        }
        if (!withinReach(mc, pos, Math.max(range.getValue(), wallsRange.getValue()))) return null;
        return new BlockHitResult(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), Direction.UP, support, false);
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
        boolean placed;
        if (swapMode.getValue() == SwapType.Normal) {
            InvHelper.swapToSlot(bedSlot, SwapType.Normal);
            placed = PlaceHelper.place(placeMode.getValue(), hit, InteractionHand.MAIN_HAND);
        } else {
            boolean swapped = InvHelper.swapToSlot(bedSlot, swapMode.getValue());
            // 2026-07-25 ("đang bed aura được thì báo silent swap failed, abort place"):
            // swapToSlot's boolean return is unreliable enough on its own (same class of
            // desync MainHand.java's own silent-swap handling documents -- "ground truth
            // every tick, never trust the flag alone") that hard-aborting the whole cycle the
            // instant it reports false wasted real placement opportunities on what was often
            // just a transient hiccup. Always resync via the SAME ServerboundSetCarriedItemPacket
            // this branch already sent (not a new mechanism) and fall through to the real
            // place() attempt regardless -- if the main hand genuinely isn't a bed, place()
            // itself just fails harmlessly instead of this pre-check aborting the cycle early
            // on a false negative.
            if (!swapped && mc.getConnection() != null) {
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(
                    mc.player.getInventory().getSelectedSlot()));
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
