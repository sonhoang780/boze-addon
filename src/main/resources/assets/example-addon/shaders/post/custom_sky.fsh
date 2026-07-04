#version 330

// Engine appends "Sampler" to each JSON sampler_name -- declaring these as plain
// Main/MainDepth/Sky left them unbound ("does not use sampler ... defined in the
// pipeline" warnings in logs) and the pass sampled nothing.
uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D SkySampler;
uniform sampler2D ParamsSampler; // r = brightness multiplier (RealTime day/night dimming)

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float depth = texture(MainDepthSampler, texCoord).r;
    vec4 base = texture(MainSampler, texCoord);

    // Only true sky pixels (nothing rasterized there = far-plane depth, exactly 1.0
    // from the GL clear) get replaced; terrain, entities, etc. pass through
    // untouched. Threshold assumes standard (non-reversed) depth convention --
    // verify in-game; if the sky ends up replacing terrain instead of the sky,
    // this comparison needs flipping (depth < 0.0001).
    //
    // MUST be a tight epsilon, not 0.9999 (4 nines): standard perspective depth is
    // extremely compressed near the far plane, so REAL geometry right at the edge
    // of render distance can legitimately read >=0.9999 while still being actual
    // terrain (finite z never rounds to exactly 1.0; only the untouched GL-cleared
    // background does). A loose threshold caught that edge terrain as "sky" and
    // flipped in/out frame-to-frame as chunks streamed/camera moved -- Nether's
    // dense cave/wall geometry sits hard against the render-distance edge far more
    // often than the overworld's open horizon, so this flickered mainly there
    // (2026-07-04).
    if (depth < 0.99999) {
        fragColor = base;
        return;
    }

    vec4 sky = texture(SkySampler, texCoord);
    // Applied HERE (single compositing point) rather than in each individual sky
    // shader (Image/StarryNight/arbitrary user .frag files) so RealTime dims every
    // mode uniformly without needing per-shader changes.
    float brightness = texelFetch(ParamsSampler, ivec2(0, 0), 0).r;
    fragColor = vec4(mix(base.rgb, sky.rgb * brightness, sky.a), base.a);
}
