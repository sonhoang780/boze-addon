#version 150

// Sampler0 = blurred framebuffer (used for refraction sampling inside the widget)
uniform sampler2D Sampler0;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// Widget geometry + optics uniforms (32 bytes, std140)
layout(std140) uniform WidgetUniforms {
    vec4 Rect;          // x, y, w, h in framebuffer pixels (y=0 at bottom)
    float CornerRadius;
    float RefThickness;
    float IOR;
    float RefDispersion;
};

out vec4 fragColor;

// Signed-distance + gradient for a rounded box (ported from ReGlass sdgBox).
// Returns vec3(dist, normalX, normalY).  Negative dist = inside the shape.
vec3 sdgBox(in vec2 p, in vec2 b, float r) {
    vec2 w = abs(p) - (b - r);
    vec2 s = sign(p);
    float g = max(w.x, w.y);
    vec2 q = max(w, 0.0);
    float l = length(q);
    float dist = (g > 0.0) ? l - r : g - r;
    vec2 n = (g > 0.0) ? (q / max(l, 1e-6)) : ((w.x > w.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    return vec3(dist, s * n);
}

void main() {
    vec2 inSize = InSize;
    if (inSize.x <= 0.0 || inSize.y <= 0.0) inSize = vec2(textureSize(Sampler0, 0));

    vec2 coord = gl_FragCoord.xy;
    vec2 uv    = coord / inSize;

    // ── SDF for widget rounded-rect ──
    vec2 center = vec2(Rect.x + Rect.z * 0.5, Rect.y + Rect.w * 0.5);
    vec2 p      = (coord - center) / inSize.y;
    vec2 b      = 0.5 * vec2(Rect.z, Rect.w) / inSize.y;
    float rad   = CornerRadius / inSize.y;

    vec3 g    = sdgBox(p, b, rad);
    float dist = g.x;
    vec2 normal = g.yz;
    float nlen  = length(normal);
    if (nlen > 1e-6) normal /= nlen;

    // Pixels outside the widget are left untouched (the glass pass only
    // writes interior pixels; shadow/glow are handled by Skia layers above).
    if (dist >= 0.0) discard;

    float nmerged = -dist * inSize.y; // depth into glass in pixels

    // ── Snell's-law refraction (identical to ReGlass) ──
    float xR     = 1.0 - nmerged / max(RefThickness, 1e-6);
    float thetaI = asin(clamp(pow(xR, 2.0), 0.0, 1.0));
    float thetaT = asin(clamp((1.0 / max(IOR, 1e-6)) * sin(thetaI), -1.0, 1.0));
    float edgeFactor = -tan(thetaT - thetaI);
    if (nmerged >= RefThickness) edgeFactor = 0.0;

    vec2 refrOffset = -normal * edgeFactor * 0.08 * vec2(inSize.y / inSize.x, 1.0);

    // ── Chromatic dispersion (same NR/NG/NB constants as ReGlass) ──
    const float NR = 0.985;
    const float NG = 1.000;
    const float NB = 1.015;
    float d = RefDispersion;
    vec4 dispPixel;
    dispPixel.r = texture(Sampler0, uv + refrOffset * (1.0 - (NR - 1.0) * d)).r;
    dispPixel.g = texture(Sampler0, uv + refrOffset * (1.0 - (NG - 1.0) * d)).g;
    dispPixel.b = texture(Sampler0, uv + refrOffset * (1.0 - (NB - 1.0) * d)).b;
    dispPixel.a = 1.0;

    // ── Minimal glass tint (nearly clear, like ReGlass tintAlpha≈0) ──
    vec4 outColor = mix(dispPixel, vec4(0.59, 0.80, 1.0, 1.0), 0.03);

    // ── Fresnel edge highlight (same formula as ReGlass) ──
    float fresnelFactor = clamp(
        pow(1.0 + dist * inSize.y / 1500.0 * pow(500.0 / max(200.0, 1e-3), 2.0), 5.0),
        0.0, 1.0);
    outColor.rgb = mix(outColor.rgb, vec3(1.0), fresnelFactor * 0.105 * nlen);

    fragColor = vec4(outColor.rgb, 1.0);
}
