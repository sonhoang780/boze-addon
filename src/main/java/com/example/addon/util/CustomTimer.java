package com.example.addon.util;

/**
 * Shared client tick-speed multiplier consumed by MixinDeltaTrackerTimer.
 * 1.0 = normal speed, >1.0 = faster (more ticks simulated per real second),
 * <1.0 = slower. Any module wanting a Timer-hack effect writes this directly.
 */
public final class CustomTimer {
    public static volatile double multiplier = 1.0;
    private CustomTimer() {}
}
