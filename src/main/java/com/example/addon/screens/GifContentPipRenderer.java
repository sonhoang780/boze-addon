package com.example.addon.screens;

import net.minecraft.client.renderer.MultiBufferSource;

/** GifHUD's dedicated Skia PiP renderer instance for the actual (rounded-corner) frame content -- see AbstractSkiaPipRenderer's class doc for why. */
public final class GifContentPipRenderer extends AbstractSkiaPipRenderer<GifContentPipState> {

    public static volatile GifContentPipRenderer ACTIVE;

    public GifContentPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<GifContentPipState> getRenderStateClass() { return GifContentPipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_gifcontent"; }
}
