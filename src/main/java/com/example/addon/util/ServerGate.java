package com.example.addon.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Gate for modules restricted to a specific server (per-module request, not a general
 * blacklist/whitelist system). getCurrentServer() is populated by vanilla for both saved
 * multiplayer entries and direct-connect sessions alike -- BUT kingmc's internal server
 * switch (spawn/game sub-servers) does a real reconnect cycle (confirmed:
 * MixinGuiInfiniteChat's Gui#onDisconnected fires on it, not just on a full disconnect),
 * and getCurrentServer() goes transiently null during that reconnect window. A naive
 * null-check read KingMCModuleGate as "left kingmc" on that single tick and permanently
 * disabled+hid PhobosAutoTotem/PhobosDoubleHand -- matching the reported "randomly
 * enabled/disabled when switching servers". Fix: stick to the last confirmed answer
 * while a connection exists (player != null); only clear it on an actual disconnect.
 */
public final class ServerGate {
    private ServerGate() {}

    private static boolean lastKnown = false;

    public static boolean isKingMC() {
        if (Minecraft.getInstance().player == null) {
            lastKnown = false;
            return false;
        }
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server != null && server.ip != null) {
            lastKnown = server.ip.toLowerCase().contains("kingmc.vn");
        }
        return lastKnown;
    }
}
