#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;       // JFA Buffer
uniform sampler2D OriginalSampler; // Original entity_outline buffer
uniform sampler2D ImageSampler;    // Chams fill image
uniform sampler2D OutlineImageSampler; // Chams outline image
uniform sampler2D ParamsSampler;   // Parameters (1x1 texture)

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // texture() is for normalized coordinates, texelFetch is for exact pixel coordinates
    vec4 params = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 outlineTint = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    vec4 flipData = texelFetch(ParamsSampler, ivec2(3, 0), 0);
    
    float fillEnabled  = params.r;
    float fillOpacity  = params.g;
    float bloomEnabled = params.b;
    float bloomRadius  = params.a * 255.0; // Unpack from 0-1 to 0-255
    
    float doFlip = flipData.r;
    float finalY = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);

    vec2 oneTexel = 1.0 / InSize;
    
    // 1. Direct interior check using OriginalSampler
    vec4 orig = texture(OriginalSampler, texCoord);
    if (orig.a > 0.0) {
        if (fillEnabled > 0.5) {
            vec4 img = texture(ImageSampler, flippedUv);
            fragColor = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
            return;
        } else {
            discard;
        }
    }

    // 2. Outside silhouette (Bloom)
    if (bloomEnabled < 0.5) {
        discard;
    }
    
    vec4 jfaData = texture(InSampler, texCoord);
    
    // If JFA data is empty, this pixel is too far from any outline
    if (jfaData.a < 0.5) {
        discard;
    }

    vec2 offset = floor(jfaData.xy * 255.0 + 0.5) - 127.0;
    float dist = length(offset);

    if (dist <= bloomRadius) {
        // Sample the original color at the closest silhouette pixel
        vec2 closestUV = texCoord - offset * oneTexel;
        vec4 originalColor = texture(OriginalSampler, closestUV);
        
        // Gaussian-like falloff for a softer glow (like KawaseBlur)
        float strength = exp(-pow(dist / (bloomRadius * 0.4), 2.0));
        
        // Boost the core so it stays solid white near the entity
        if (dist <= 1.5) {
            strength = 1.0;
        } else if (dist <= 3.0) {
            strength = mix(1.0, strength, (dist - 1.5) / 1.5);
        }

        vec4 outlineCol = texture(OutlineImageSampler, flippedUv);
        fragColor = vec4(outlineCol.rgb * outlineTint.rgb, outlineCol.a * strength * outlineTint.a);
    } else {
        discard;
    }
}
