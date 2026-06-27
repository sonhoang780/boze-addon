package com.example.addon.screens;

import io.github.humbleui.skija.Canvas;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

import java.util.function.Consumer;

/**
 * Render-state for {@link SkiaPipRenderer}: a rectangular HUD region painted with GPU
 * Skija, registered via the normal {@code GuiRenderState.addPicturesInPictureState}
 * mechanism so it gets the SAME extraction-order Z-sorting as every other GUI element
 * (HUD, then whatever Screen got extracted after it) — unlike drawing at the literal
 * end of the frame, which is always on top of everything regardless of what else is open.
 *
 * @param painter draws onto a Canvas already translated so (0,0) is the top-left of
 *                (x0,y0) in GUI-logical pixels — i.e. paint using the SAME absolute
 *                coordinates you'd use with a normal GuiGraphicsExtractor.
 */
public record SkiaPipState(Consumer<Canvas> painter, int x0, int y0, int x1, int y1) implements PictureInPictureRenderState {

    @Override
    public float scale() { return 1.0f; }

    @Override
    public ScreenRectangle scissorArea() { return null; }

    @Override
    public ScreenRectangle bounds() {
        return PictureInPictureRenderState.getBounds(x0, y0, x1, y1, null);
    }
}
