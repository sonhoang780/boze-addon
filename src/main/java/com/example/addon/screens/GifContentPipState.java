package com.example.addon.screens;

import io.github.humbleui.skija.Canvas;

import java.util.function.Consumer;

/** GifHUD's dedicated Skia PiP state for the actual (rounded-corner) frame content -- see AbstractSkiaPipRenderer's class doc for why this needs its own type. */
public record GifContentPipState(Consumer<Canvas> painter, int x0, int y0, int x1, int y1) implements SkiaPaintedState {}
