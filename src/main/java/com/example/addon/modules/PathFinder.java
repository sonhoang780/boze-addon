package com.example.addon.modules;

import dev.babbaj.pathfinder.NetherPathfinder;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;

public class PathFinder extends AddonModule {

    public static final PathFinder INSTANCE = new PathFinder();

    public final SliderOption maxHeight = new SliderOption(this, "Max Height",
        "Nether ceiling height assumption passed to the pathfinder.", 128.0, 1.0, 255.0, 1.0);
    public final SliderOption flySpeed = new SliderOption(this, "Fly Speed",
        "Horizontal flight speed.", 1.0, 0.1, 3.0, 0.05);
    public final SliderOption vertSpeed = new SliderOption(this, "Vertical Speed",
        "Vertical flight speed.", 0.6, 0.1, 2.0, 0.05);

    private long context = 0;
    public BlockPos goal = null;
    public Long seed = null;
    public boolean flying = false;

    private PathFinder() {
        super("PathFinder", "Nether-ceiling elytra pathfinder (babbaj/nether-pathfinder algorithm).");
    }

    @Override
    public void onEnable() {
        if (!NetherPathfinder.isThisSystemSupported()) {
            System.err.println("[PathFinder] Native library failed to load; module will do nothing.");
            return;
        }
        context = NetherPathfinder.newContext(seed != null ? seed : 0L, null,
            NetherPathfinder.DIMENSION_NETHER, maxHeight.getValue().intValue(), true);
    }

    @Override
    public void onDisable() {
        if (context != 0) {
            NetherPathfinder.freeContext(context);
            context = 0;
        }
        goal = null;
        flying = false;
    }

    public long getContext() {
        return context;
    }

    public boolean isReady() {
        return getState() && context != 0 && NetherPathfinder.isThisSystemSupported();
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        // Chunk-feed and flight logic added in Task 3/4.
    }
}
