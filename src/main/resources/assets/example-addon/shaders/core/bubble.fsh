#version 330

// Soap-bubble fragment shader: per-pixel fresnel rim, view-dependent thin-film
// iridescence (the rainbow sheen of a real soap film), one bright specular
// highlight and a darker anti-light side -- so the sphere reads as 3D, not one
// flat color. Animated by GameTime (Globals uniform block). Alpha comes from
// fresnel (rim strong, center nearly transparent) * vertexColor.a (fade of
// detached small bubbles).

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 viewPos;
in vec3 viewNormal;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec3 N = normalize(viewNormal);
    vec3 V = normalize(-viewPos);
    // abs(): backfaces (inside of the far half of the bubble) shade like front
    // faces instead of going black -- culling is off so both halves are visible.
    float ndv = abs(dot(N, V));

    float fresnel = pow(1.0 - ndv, 2.5);

    float t = GameTime * 1200.0;

    // Thin-film "shimmer": optical thickness varies over the film (position
    // pattern that slowly flows) and with the viewing angle. Only used as a
    // BRIGHTNESS wobble (filmLum below), not an independent RGB rainbow -- three
    // out-of-phase channels multiplied straight into the color fought the
    // BubbleColor option (each channel varying on its own always shows some
    // rainbow no matter what tint scales it by). Hue now comes from tint alone.
    float thickness = 2.5 * (1.0 - ndv)
        + 0.55 * sin(texCoord0.x * 12.566 + t * 0.35)
        + 0.40 * cos(texCoord0.y * 9.42 - t * 0.27);
    vec3 film = 0.5 + 0.45 * cos(vec3(
        thickness * 6.0,
        thickness * 6.0 + 2.1,
        thickness * 6.0 + 4.2
    ));
    float filmLum = (film.r + film.g + film.b) / 3.0;

    // Fixed key light from upper-front-left in view space: one bright glint,
    // opposite side falls dark -- the "cho sang cho toi" 3D cue.
    vec3 L = normalize(vec3(-0.45, 0.72, 0.53));
    float diffuse = 0.35 + 0.65 * max(dot(N, L), 0.0);
    float spec = pow(max(dot(reflect(-L, N), V), 0.0), 48.0);

    vec3 tint = vertexColor.rgb; // user-configurable BubbleColor, carried per-vertex
    vec3 color = tint * (filmLum * diffuse) + vec3(1.0) * spec * 0.9;

    float alpha = clamp(fresnel * 0.85 + spec * 0.6 + 0.06, 0.0, 1.0);
    alpha *= vertexColor.a;

    fragColor = vec4(color, alpha) * ColorModulator;
}
