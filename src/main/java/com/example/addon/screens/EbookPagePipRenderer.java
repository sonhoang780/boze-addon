package com.example.addon.screens;

import net.minecraft.client.renderer.MultiBufferSource;

/** EbookReader's dedicated Skia PiP renderer instance -- see AbstractSkiaPipRenderer's class doc for why. */
public final class EbookPagePipRenderer extends AbstractSkiaPipRenderer<EbookPagePipState> {

    public static volatile EbookPagePipRenderer ACTIVE;

    public EbookPagePipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<EbookPagePipState> getRenderStateClass() { return EbookPagePipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip_ebookpage"; }
}
