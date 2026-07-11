package com.example.addon.screens;

import net.minecraft.client.renderer.MultiBufferSource;

/** WebBrowser's dedicated Skia PiP renderer instance for the tile/screen drop shadow -- see AbstractSkiaPipRenderer's class doc for why. */
public final class WebBrowserShadowPipRenderer extends AbstractSkiaPipRenderer<WebBrowserShadowPipState> {

    public static volatile WebBrowserShadowPipRenderer ACTIVE;

    public WebBrowserShadowPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<WebBrowserShadowPipState> getRenderStateClass() { return WebBrowserShadowPipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_webbrowser_shadow"; }
}
