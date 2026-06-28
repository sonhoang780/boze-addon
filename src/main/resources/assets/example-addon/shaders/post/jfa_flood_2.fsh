#version 330
layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / InSize;
    vec4 bestData = vec4(0.0);
    float bestDistSq = 999999.0;
    
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 sampleUV = texCoord + vec2(float(x), float(y)) * 2.0 * oneTexel;
            if (sampleUV.x < 0.0 || sampleUV.x > 1.0 || sampleUV.y < 0.0 || sampleUV.y > 1.0) continue;
            
            vec4 data = texture(InSampler, sampleUV);
            if (data.a > 0.5) {
                vec2 offset = floor(data.xy * 255.0 + 0.5) - 127.0;
                vec2 newOffset = offset + vec2(float(x), float(y)) * 2.0;
                
                // Clamp to prevent overflow
                newOffset = clamp(newOffset, vec2(-127.0), vec2(127.0));
                
                float distSq = dot(newOffset, newOffset);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestData = vec4((newOffset + 127.0) / 255.0, 0.0, 1.0);
                }
            }
        }
    }
    fragColor = bestData;
}
