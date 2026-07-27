package com.example.addon.screens;

import net.minecraft.client.renderer.MultiBufferSource;

/** ComposeScreen's dedicated Skia PiP renderer instance -- see AbstractSkiaPipRenderer's class doc for why. */
public final class ComposeContentPipRenderer extends AbstractSkiaPipRenderer<ComposeContentPipState> {

    public static volatile ComposeContentPipRenderer ACTIVE;

    public ComposeContentPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<ComposeContentPipState> getRenderStateClass() { return ComposeContentPipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_compose"; }
}
