package com.example.addon.screens;

import io.github.humbleui.skija.Canvas;

import java.util.function.Consumer;

/** WebBrowser's dedicated Skia PiP state -- see AbstractSkiaPipRenderer's class doc for why this needs its own type. */
public record WebBrowserPipState(Consumer<Canvas> painter, int x0, int y0, int x1, int y1) implements SkiaPaintedState {}
