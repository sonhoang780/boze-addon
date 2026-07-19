package com.example.addon.render;

import java.util.UUID;

/**
 * Duck interface added to EntityRenderState (MixinEntityRenderStateGelUuid) so
 * GelParticleSystem can correlate a render-state snapshot back to a stable player
 * UUID at flush time (ModelFeatureRenderer.renderModel, see MixinModelFeatureRenderer)
 * -- render states carry no entity reference by design, and x/y/z/entityType alone
 * aren't a stable per-entity key across frames.
 *
 * Deliberately NOT in com.example.addon.mixin: that package is reserved by
 * example-addon.mixins.json for actual @Mixin classes only -- Mixin's transformer
 * intercepts direct class-loads of anything else placed there and throws
 * IllegalClassLoadError (crashed resourcepack loading on startup, black window,
 * 2026-07-13), since a plain interface referenced/implemented by multiple mixins
 * gets loaded outside the mixin-apply path.
 */
public interface GelUuidCarrier {
    UUID exampleAddon$getGelUuid();
    void exampleAddon$setGelUuid(UUID id);
}
