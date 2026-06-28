#version 330

uniform sampler2D InSampler;
uniform sampler2D ParamsSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

in vec2 texCoord;
out vec4 fragColor;

float decodeFloat24(vec4 rgba, float minVal, float maxVal) {
    float normalized = (rgba.r * 255.0 + rgba.g * 255.0 * 256.0 + rgba.b * 255.0 * 65536.0) / 16777215.0;
    return mix(minVal, maxVal, clamp(normalized, 0.0, 1.0));
}

vec4 getVal(int i) {
    int x = i % 8;
    int y = i / 8;
    return texelFetch(ParamsSampler, ivec2(x, y), 0);
}

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i + vec3(0,0,0)), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float f = 0.0;
    f += 0.5000 * noise(p); p *= 2.02;
    f += 0.2500 * noise(p); p *= 2.03;
    f += 0.1250 * noise(p); p *= 2.01;
    f += 0.0625 * noise(p);
    return f;
}

uniform sampler2D SDFSampler;

vec3 getSdfUv(vec3 localPos) {
    vec3 boxMin = vec3(-0.6113514, -1.1403344, -0.65995836);
    vec3 boxSize = vec3(1.2276337, 2.2812889, 1.3173767);
    
    vec3 uvw = (localPos - boxMin) / boxSize;
    return uvw;
}

float sampleSdf(vec3 localPos) {
    vec3 uvw = getSdfUv(localPos);
    if(uvw.x < 0.0 || uvw.x > 1.0 || uvw.y < 0.0 || uvw.y > 1.0 || uvw.z < 0.0 || uvw.z > 1.0) return 1.0;
    
    float zCoord = uvw.z * 31.0;
    float zIndex = floor(zCoord);
    float zFract = fract(zCoord);
    
    float col0 = mod(zIndex, 8.0);
    float row0 = floor(zIndex / 8.0);
    vec2 uv0 = vec2((col0 + uvw.x) / 8.0, (row0 + uvw.y) / 4.0);
    
    float zNext = min(zIndex + 1.0, 31.0);
    float col1 = mod(zNext, 8.0);
    float row1 = floor(zNext / 8.0);
    vec2 uv1 = vec2((col1 + uvw.x) / 8.0, (row1 + uvw.y) / 4.0);
    
    float val0 = texture(SDFSampler, uv0).r;
    float val1 = texture(SDFSampler, uv1).r;
    
    float d = mix(val0, val1, zFract);
    return 1.0 - d; // Convert inverse distance back to distance (0 = surface)
}

void main() {
    vec4 baseColor = texture(InSampler, texCoord);
    
    float fadeAlpha = decodeFloat24(getVal(15), 0.0, 1.0);
    if(fadeAlpha <= 0.0) {
        fragColor = baseColor;
        return;
    }
    
    vec3 tl = vec3(decodeFloat24(getVal(0), -2.0, 2.0), decodeFloat24(getVal(1), -2.0, 2.0), decodeFloat24(getVal(2), -2.0, 2.0));
    vec3 tr = vec3(decodeFloat24(getVal(3), -2.0, 2.0), decodeFloat24(getVal(4), -2.0, 2.0), decodeFloat24(getVal(5), -2.0, 2.0));
    vec3 bl = vec3(decodeFloat24(getVal(6), -2.0, 2.0), decodeFloat24(getVal(7), -2.0, 2.0), decodeFloat24(getVal(8), -2.0, 2.0));
    vec3 br = vec3(decodeFloat24(getVal(9), -2.0, 2.0), decodeFloat24(getVal(10), -2.0, 2.0), decodeFloat24(getVal(11), -2.0, 2.0));
    
    vec3 tungPos = vec3(decodeFloat24(getVal(12), -64.0, 64.0), decodeFloat24(getVal(13), -64.0, 64.0), decodeFloat24(getVal(14), -64.0, 64.0));
    float time = decodeFloat24(getVal(16), 0.0, 1000.0);
    
    vec3 topDir = mix(tl, tr, texCoord.x);
    vec3 bottomDir = mix(bl, br, texCoord.x);
    vec3 rayDir = normalize(mix(bottomDir, topDir, texCoord.y));
    
    vec3 rayOri = vec3(0.0);
    
    float radius = 1.8;
    vec3 oc = rayOri - tungPos;
    float b = dot(oc, rayDir);
    float c = dot(oc, oc) - radius * radius;
    float h = b * b - c;
    
    if(h < 0.0) {
        fragColor = baseColor;
        return;
    }
    
    h = sqrt(h);
    float t1 = -b - h;
    float t2 = -b + h;
    
    float tMin = max(t1, 0.0);
    float tMax = max(t2, 0.0);
    
    if(tMin >= tMax) {
        fragColor = baseColor;
        return;
    }
    
    int MAX_STEPS = 40;
    float stepSize = (tMax - tMin) / float(MAX_STEPS);
    float transmittance = 1.0;
    vec3 scatterColor = vec3(0.0);
    vec3 lightDir = normalize(vec3(0.5, 1.0, -0.3));
    
    float t = tMin;
    for(int i = 0; i < MAX_STEPS; i++) {
        vec3 p = rayOri + rayDir * t;
        vec3 localPos = p - tungPos;
        
        float distToSurface = sampleSdf(localPos) * 0.9494037; // Scale back by maxDist
        
        // Use SDF distance to determine density
        float densityMask = smoothstep(0.15, 0.0, distToSurface);
        
        if (densityMask > 0.01) {
            vec3 noisePos = p * 2.0 + vec3(0.0, -time * 1.5, 0.0);
            float dens = fbm(noisePos) * densityMask;
            dens *= 3.0 * fadeAlpha;
            
            if(dens > 0.01) {
                float lDens = fbm(noisePos + lightDir * 0.5) * smoothstep(0.15, 0.0, sampleSdf(localPos + lightDir * 0.2));
                float lTrans = exp(-lDens * 2.0);
                
                float stepTrans = exp(-dens * stepSize);
                transmittance *= stepTrans;
                
                vec3 ambient = vec3(0.3, 0.35, 0.4);
                vec3 sunColor = vec3(1.0, 0.9, 0.8) * lTrans;
                vec3 currentLight = ambient + sunColor;
                
                scatterColor += transmittance * currentLight * dens * stepSize;
            }
        }
        
        if(transmittance < 0.01) break;
        t += stepSize;
    }
    
    vec3 finalColor = baseColor.rgb * transmittance + scatterColor;
    fragColor = vec4(finalColor, baseColor.a);
}
