#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;
uniform sampler2D ImageSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // texelFetch is for exact pixel coordinates
    vec4 params = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 outlineTint = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    vec4 flipData = texelFetch(ParamsSampler, ivec2(3, 0), 0);
    
    float fillEnabled = params.r; // 0=Off, 1=Color, 2=Image, 3=Shader
    float fillOpacity = params.g;
    
    float outlineOpacity = flipData.g;
    float doFlip = flipData.r;
    float finalY = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);
    
    vec4 orig = texture(InSampler, texCoord);
    
    if (orig.a > 0.0) {
        if (fillEnabled > 0.5) {
            vec4 img = texture(ImageSampler, flippedUv);
            fragColor = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
        } else {
            // No fill, we discard so the interior is transparent!
            discard;
        }
    } else {
        if (outlineOpacity > 0.0) {
            vec2 oneTexel = 1.0 / OutSize;
            float alphaSum = 
                texture(InSampler, texCoord + vec2(oneTexel.x, 0.0)).a +
                texture(InSampler, texCoord - vec2(oneTexel.x, 0.0)).a +
                texture(InSampler, texCoord + vec2(0.0, oneTexel.y)).a +
                texture(InSampler, texCoord - vec2(0.0, oneTexel.y)).a;
            
            if (alphaSum > 0.0) {
                // Pixel is adjacent to the entity
                fragColor = vec4(outlineTint.rgb, outlineOpacity * outlineTint.a);
                return;
            }
        }
        
        fragColor = vec4(0.0);
    }
}
