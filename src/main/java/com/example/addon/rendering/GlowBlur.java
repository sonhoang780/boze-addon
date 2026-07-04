package com.example.addon.rendering;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.resources.Identifier;

/**
 * Holds the shared "glow field" texture that {@link JfaField} writes into every
 * frame and entity_outline.json/hand_outline.json/fill_only_*.json's resolve pass
 * reads from as the "In" location input (`example-addon:chamsglow`).
 *
 * <p>Previously this class also implemented a dual-Kawase blur pyramid that wrote
 * this same texture; that was replaced by JfaField's jump-flood distance field
 * (see docs/superpowers/specs/2026-07-04-jfa-glow-design.md) -- the Kawase pyramid's
 * 8-bit RGBA8 field caused visible concentric banding once the halo remap in
 * glow_resolve.fsh stretched a thin alpha slice into the full visible gradient.
 * The texture identifier/registration is unaffected by that swap (JFA writes
 * distance+handness into the same GL texture id instead of a blurred field), so
 * this holder class is kept as-is and only the pyramid code was removed.
 */
public class GlowBlur {
    public static final Identifier GLOW_TEX_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/chamsglow.png");
    public static final ChamsImageTexture GLOW_TEXTURE = new ChamsImageTexture();

    public static void registerTextures() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            GLOW_TEXTURE.init();
            mc.getTextureManager().register(GLOW_TEX_ID, GLOW_TEXTURE);
        });
    }
}
