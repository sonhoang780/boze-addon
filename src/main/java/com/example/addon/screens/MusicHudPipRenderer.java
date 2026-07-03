package com.example.addon.screens;

import net.minecraft.client.renderer.MultiBufferSource;

/** MusicHUD's dedicated Skia PiP renderer instance -- see AbstractSkiaPipRenderer's class doc for why. */
public final class MusicHudPipRenderer extends AbstractSkiaPipRenderer<MusicHudPipState> {

    public static volatile MusicHudPipRenderer ACTIVE;

    public MusicHudPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<MusicHudPipState> getRenderStateClass() { return MusicHudPipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_musichud"; }
}
