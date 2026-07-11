#version 330

// Fragment half of a custom RenderPipeline for AuraStep's world-space decal quads.
// Reuses core/entity.vsh UNCHANGED (matrices, fog distances, per-face lighting,
// lightmap, overlay) -- only this fragment shader differs, replacing the usual
// Sampler0 texture sample with a procedural water/fire look driven by the quad's
// own local UV (texCoord0, 0..1) and the engine's free-running GameTime uniform
// (from Globals) for animation. AURASTEP_FIRE is a compile-time #define selecting
// which pipeline variant (water vs fire) this shader was built as.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif

in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

// Port of "A simple water effect" by Tom@2016 (shadertoy, based on Hugo
// Elias' height-field water tutorial): sample a height buffer, take its
// gradient via neighbor texels, fake-shade that gradient as a normal
// (diffuse + specular), tint with a background texture warped by the
// gradient. The original's height buffer comes from a real ping-pong wave
// SIMULATION (buffer A stepping a discrete wave equation frame to frame) --
// building that here would mean a whole new persistent-FBO subsystem for a
// world-space ribbon quad (no fixed texel grid to simulate on). Not done.
// Instead the height field is the same kind of closed-form wave sum AuraStep
// already used (a plane-wave sum, one cosine "train" per angle) and the
// gradient is taken ANALYTICALLY (chain rule through the wave sum) rather
// than by finite-differencing neighbor samples -- no epsilon/step-size to
// mistune, so it stays exactly as smooth as the player moves as it does
// standing still (the old finite-difference version's fixed 1/60 UV step
// didn't scale with anything, which is what read as "not smooth" in motion).
const float WATER_PI = 3.1415926535897932;
const float WATER_SPEED = 0.2;
const float WATER_SPEED_X = 0.3;
const float WATER_SPEED_Y = 0.3;
const float WATER_INTENSITY = 2.4;
const int   WATER_STEPS = 8;
const float WATER_FREQUENCY = 6.0;
const int   WATER_ANGLE = 7; // prime, per original author's note
const float WATER_EMBOSS_STRENGTH = 3.0;

// Returns the wave sum's height (cos(c), c = sum of plane-wave terms) AND the
// analytic partial derivatives of the INNER sum c w.r.t. p.x/p.y, via dOut.
float waterHeight(vec2 p, float time, out vec2 dcdp) {
    float deltaTheta = 2.0 * WATER_PI / float(WATER_ANGLE);
    float c = 0.0;
    dcdp = vec2(0.0);
    for (int i = 0; i < WATER_STEPS; i++) {
        float theta = deltaTheta * float(i);
        float ct = cos(theta), st = sin(theta);
        // Mirrors the original's adjc construction (coord shifted per-angle before
        // the rotate+cos) -- p only enters through this rotated-and-shifted term,
        // so its p-derivative is exactly ct/-st scaled by the outer cos's slope.
        float shiftX = ct * time * WATER_SPEED + time * WATER_SPEED_X;
        float shiftY = -(st * time * WATER_SPEED - time * WATER_SPEED_Y);
        float arg = ((p.x + shiftX) * ct - (p.y + shiftY) * st) * WATER_FREQUENCY;
        c += cos(arg) * WATER_INTENSITY;
        float dArg = -sin(arg) * WATER_FREQUENCY * WATER_INTENSITY;
        dcdp.x += dArg * ct;
        dcdp.y += -dArg * st;
    }
    float h = cos(c);
    float dh = -sin(c);
    dcdp *= dh; // chain rule: d(cos(c))/dp = -sin(c) * dc/dp
    return h;
}

// uv.x = cumulative arc length along the ribbon (blocks), uv.y = 0..1 across --
// the wave field is a continuous function of arc length, so the pattern flows
// unbroken across segment joints instead of restarting per quad.
vec4 waterEffect(vec2 uv, float t) {
    vec2 p = vec2(uv.x * 0.6, uv.y);
    vec2 dh;
    waterHeight(p, t, dh);

    vec3 grad = normalize(vec3(dh * WATER_EMBOSS_STRENGTH, 1.0));
    vec3 light = normalize(vec3(0.2, -0.5, 0.7));
    float diffuse = dot(grad, light);
    float spec = pow(max(0.0, -reflect(light, grad).z), 32.0);

    // No deep/shallow blue tint -- clear water, just shaded by the wave normal
    // (diffuse+specular) so grass shows through underneath via alpha.
    vec3 color = vec3(1.0) * max(diffuse, 0.0) + vec3(spec);

    // Soft fade across the ribbon width (uv.y: 0 and 1 are the edges).
    float acrossFade = smoothstep(0.0, 0.25, uv.y) * (1.0 - smoothstep(0.75, 1.0, uv.y));
    float waterAlpha = clamp(0.55 + spec * 0.4, 0.0, 1.0) * acrossFade;
    return vec4(color, waterAlpha);
}

// Port of "3D Fire [340]" (fork by Xor, shadertoy.com/view/3XXSWS), used as
// originally intended: a small self-contained "camera looking at a flame"
// raymarch, one per vertical billboard AuraStep now plants at each footstep
// (not painted onto a flat ground ribbon -- that always reads as a 2D texture
// no matter how volumetric the shader's own math is, because the surface
// carrying it is flat and horizontal). Each billboard gets BOTH uv axes
// centered (-1..1, the original's real screen mapping), so the raymarched
// flame reads as an actual 3D silhouette that occludes/parallaxes with
// viewing angle instead of a flat painted pattern.
vec4 fireEffect(vec2 localUv, float t) {
    vec2 uv = localUv * 2.0 - 1.0;

    vec3 rayOrigin = vec3(0.0);
    vec3 camPos = vec3(0.0, 0.0, 1.0);
    vec3 dir0 = normalize(-camPos);
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(dir0, up));
    up = cross(right, dir0);

    vec3 rayDirection = normalize(dir0 + up * uv.y + right * uv.x);

    float rayDepth = 0.0;
    vec3 col = vec3(0.0);

    for (int i = 0; i < 50; i++) {
        vec3 hitPoint = rayOrigin + rayDepth * rayDirection;
        hitPoint.z += 5.0 + cos(t);

        float rotationAngle = hitPoint.y * 0.5;
        float cs = cos(rotationAngle), sn = sin(rotationAngle);
        mat2 rot = mat2(cs, -sn, sn, cs);
        hitPoint.xz *= rot;

        hitPoint.xz /= max(hitPoint.y * 0.1 + 1.0, 0.1);

        float turbulenceFrequency = 2.0;
        for (int j = 0; j < 5; j++) {
            vec3 turbulenceOffset = cos((hitPoint.yzx - vec3(t / 0.1, t, turbulenceFrequency)) * turbulenceFrequency);
            hitPoint += turbulenceOffset / turbulenceFrequency;
            turbulenceFrequency /= 0.6;
        }

        float coneRadius = length(hitPoint.xz);
        float coneDistance = abs(coneRadius + hitPoint.y * 0.3 - 0.5);

        float stepSize = 0.01 + coneDistance / 7.0;
        rayDepth += stepSize;

        // Single scalar channel, not per-channel-phase-shifted RGB -- the old
        // vec3(7.0, 2.0, 3.0) phase offset made each color channel oscillate
        // independently with rayDepth, so the dominant hue (yellow/cyan/black)
        // swung with whatever depth happened to be visible from the current
        // camera angle. One channel means one intensity value, no hue to swing.
        float g = sin(rayDepth / 3.0 + 2.0) + 1.1;
        col += vec3(g) / stepSize;
    }

    col = tanh(col / 2000.0);
    float intensity = clamp(col.r, 0.0, 1.0); // r==g==b now (single-channel accumulation)
    const vec3 LIGHT_BLUE = vec3(0.55, 0.82, 1.0);
    col = mix(vec3(0.0), LIGHT_BLUE, intensity); // fixed black -> light blue ramp, no hue shift
    float alpha = intensity;
    // Soft fade on all four billboard edges so the flame silhouette never sits
    // on a hard rectangular cutoff: left/right, the bottom edge where the
    // billboard meets the grass, AND the top edge (missing before -- that's
    // what showed as a visible rectangle frame around each flame column).
    float sideFade = smoothstep(0.0, 0.15, localUv.x) * (1.0 - smoothstep(0.85, 1.0, localUv.x));
    float groundFade = smoothstep(0.0, 0.22, localUv.y);
    float topFade = 1.0 - smoothstep(0.75, 1.0, localUv.y);
    return vec4(col, alpha * sideFade * groundFade * topFade);
}

void main() {
    // GameTime's exact scale/units aren't confirmed against a running client --
    // this multiplier is a starting guess, tune it if the animation looks off.
    float t = GameTime * 1200.0;

#ifdef AURASTEP_FIRE
    vec4 effect = fireEffect(texCoord0, t);
#else
    vec4 effect = waterEffect(texCoord0, t);
#endif

#ifdef PER_FACE_LIGHTING
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    vec4 faceVertexColor = vertexColor;
#endif

    vec4 color = effect * faceVertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#ifndef AURASTEP_FIRE
    // Fire is self-illuminated (like vanilla fire/lava -- never shaded by world
    // light), same as the old behavior looked BEFORE this multiply darkened it
    // toward black on any tile that isn't fully lit. Water still takes lightmap.
    color *= lightMapColor;
#endif

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
