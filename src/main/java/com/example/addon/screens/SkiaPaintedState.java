package com.example.addon.screens;

import io.github.humbleui.skija.Canvas;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

import java.util.function.Consumer;

/**
 * Common shape shared by every per-consumer Skia PiP state record (one record type
 * per consumer -- see AbstractSkiaPipRenderer's class doc for why a shared record type
 * across multiple simultaneously-visible consumers is exactly the bug this avoids).
 *
 * @param painter draws onto a Canvas already translated so (0,0) is the top-left of
 *                (x0,y0) in GUI-logical pixels — i.e. paint using the SAME absolute
 *                coordinates you'd use with a normal GuiGraphicsExtractor.
 */
public interface SkiaPaintedState extends PictureInPictureRenderState {
    Consumer<Canvas> painter();
    int x0();
    int y0();
    int x1();
    int y1();

    @Override
    default float scale() { return 1.0f; }

    @Override
    default ScreenRectangle scissorArea() { return null; }

    @Override
    default ScreenRectangle bounds() {
        return PictureInPictureRenderState.getBounds(x0(), y0(), x1(), y1(), null);
    }
}
