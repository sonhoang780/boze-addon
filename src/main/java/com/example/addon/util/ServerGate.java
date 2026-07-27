package com.example.addon.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Gate for modules restricted to a specific server (per-module request, not a general
 * blacklist/whitelist system). getCurrentServer() is populated by vanilla for both saved
 * multiplayer entries and direct-connect sessions alike.
 */
public final class ServerGate {
    private ServerGate() {}

    public static boolean isKingMC() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase().contains("kingmc.vn");
    }
}
