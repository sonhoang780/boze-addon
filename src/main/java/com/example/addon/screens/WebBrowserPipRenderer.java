package com.example.addon.screens;

import net.minecraft.client.renderer.MultiBufferSource;

/** WebBrowser's dedicated Skia PiP renderer instance -- see AbstractSkiaPipRenderer's class doc for why. */
public final class WebBrowserPipRenderer extends AbstractSkiaPipRenderer<WebBrowserPipState> {

    public static volatile WebBrowserPipRenderer ACTIVE;

    public WebBrowserPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<WebBrowserPipState> getRenderStateClass() { return WebBrowserPipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_webbrowser"; }
}
