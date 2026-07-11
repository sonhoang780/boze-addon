package com.example.addon.screens;

import io.github.humbleui.skija.Canvas;

import java.util.function.Consumer;

/**
 * WebBrowserScreen's dedicated Skia PiP state for the tab strip + nav row chrome bar --
 * see AbstractSkiaPipRenderer's class doc for why this needs its own type. Deliberately
 * ONE state per frame drawing the WHOLE chrome bar (all tabs + all nav buttons) in a single
 * painter call, not one state per button -- two elements dispatching through the same
 * renderer instance in the same frame stomp each other's shared offscreen texture (see the
 * class doc's MusicHUD/GifHUD incident), so N simultaneous per-button states of this same
 * type would hit exactly that bug.
 */
public record WebBrowserChromePipState(Consumer<Canvas> painter, int x0, int y0, int x1, int y1) implements SkiaPaintedState {}
