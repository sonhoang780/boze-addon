package com.example.addon.screens;

import net.minecraft.client.renderer.MultiBufferSource;

/** GifHUD's dedicated Skia PiP renderer instance -- see AbstractSkiaPipRenderer's class doc for why. */
public final class GifShadowPipRenderer extends AbstractSkiaPipRenderer<GifShadowPipState> {

    public static volatile GifShadowPipRenderer ACTIVE;

    public GifShadowPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<GifShadowPipState> getRenderStateClass() { return GifShadowPipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_gifshadow"; }
}
