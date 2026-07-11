package com.example.addon.screens;

import net.minecraft.client.renderer.MultiBufferSource;

/** WebBrowserScreen's dedicated Skia PiP renderer instance for the chrome bar -- see AbstractSkiaPipRenderer's class doc for why. */
public final class WebBrowserChromePipRenderer extends AbstractSkiaPipRenderer<WebBrowserChromePipState> {

    public static volatile WebBrowserChromePipRenderer ACTIVE;

    public WebBrowserChromePipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<WebBrowserChromePipState> getRenderStateClass() { return WebBrowserChromePipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_webbrowser_chrome"; }
}
