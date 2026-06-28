#version 330

uniform sampler2D InSampler;
uniform sampler2D ImageSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // texelFetch is for exact pixel coordinates
    vec4 params = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 flipData = texelFetch(ParamsSampler, ivec2(3, 0), 0);
    
    float fillEnabled = params.r; // 0=Off, 1=Color, 2=Image, 3=Shader
    float fillOpacity = params.g;
    
    float doFlip = flipData.r;
    float finalY = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);
    
    vec4 orig = texture(InSampler, texCoord);
    
    if (orig.a > 0.0 && fillEnabled > 0.5) {
        vec4 img = texture(ImageSampler, flippedUv);
        fragColor = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
    } else {
        fragColor = vec4(0.0);
    }
}
