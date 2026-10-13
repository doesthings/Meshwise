#include "shaders.h"

#include <vector>

namespace fsv {

namespace shaders {

// Shared part-state lookup. Each part owns 4 RGBA32F texels:
//   0: base colour (sRGB) + alpha
//   1: offset xyz + flags
//   2: roughness, metalness, clearcoat, unused
//   3: section cap colour (sRGB)
#define FSV_PART_LOOKUP                                                                      \
    "uniform highp sampler2D uPartTex;\n"                                                    \
    "uniform int uPartTexWidth;\n"                                                           \
    "vec4 partTexel(uint part, int i) {\n"                                                   \
    "  int idx = int(part) * 4 + i;\n"                                                       \
    "  return texelFetch(uPartTex, ivec2(idx % uPartTexWidth, idx / uPartTexWidth), 0);\n"  \
    "}\n"

const char* kMeshVS = R"(
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNormal;
layout(location = 2) in vec4 aColor;
layout(location = 3) in uint aPart;
layout(location = 4) in float aScalar;
uniform mat4 uViewProj;
uniform int uPass;  // 0 = opaque parts, 1 = ghosted parts
)" FSV_PART_LOOKUP R"(
out vec3 vWorldPos;
out vec3 vNormal;
out vec4 vColor;
out float vScalar;
flat out vec4 vMaterial;  // roughness, metalness, flags, clearcoat
flat out vec3 vCapColor;

vec3 toLinear(vec3 c) { return c * (c * (c * 0.305306011 + 0.682171111) + 0.012522878); }

void main() {
  vec4 t1 = partTexel(aPart, 1);
  int flags = int(t1.w + 0.5);
  bool ghost = (flags & 16) != 0;
  if ((flags & 1) == 0 || (uPass == 0 && ghost) || (uPass == 1 && !ghost)) {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    return;
  }
  vec4 t0 = partTexel(aPart, 0);
  vec4 t2 = partTexel(aPart, 2);
  vec4 t3 = partTexel(aPart, 3);
  vec3 wp = aPos + t1.xyz;
  vWorldPos = wp;
  vNormal = aNormal.xyz;
  vec4 c = (flags & 4) != 0 ? aColor : t0;
  vColor = vec4(toLinear(c.rgb), c.a);
  vScalar = aScalar;
  vMaterial = vec4(t2.x, t2.y, float(flags), t2.z);
  vCapColor = toLinear(t3.rgb);
  gl_Position = uViewProj * vec4(wp, 1.0);
}
)";

const char* kMeshFS = R"(
precision highp float;
precision highp int;
in vec3 vWorldPos;
in vec3 vNormal;
in vec4 vColor;
in float vScalar;
flat in vec4 vMaterial;
flat in vec3 vCapColor;
uniform mat4 uViewProj;
uniform vec3 uCamPos;
uniform vec3 uCamForward;
uniform vec3 uCamRight;
uniform vec3 uCamUp;
uniform int uOrtho;
uniform vec3 uLightDir[3];
uniform vec3 uLightColor[3];
uniform vec3 uSkyColor;
uniform vec3 uGroundColor;
uniform float uEnvStrength;
uniform float uExposure;
uniform int uMode;          // 0 shaded, 1 thickness gradient, 2 thickness threshold, 3 overhang, 4 normals
uniform vec4 uScalarRange;  // min, max, threshold, unused
uniform float uOverhangSin;
uniform float uBedZ;
uniform vec3 uSelectColor;
uniform int uPass;
uniform vec4 uClipPlane;
uniform float uHatchSpacing;
uniform int uHatch;
uniform int uCapPass;
out vec4 fragColor;

const float PI = 3.14159265;

vec3 toSrgb(vec3 c) {
  vec3 s1 = sqrt(c), s2 = sqrt(s1), s3 = sqrt(s2);
  return clamp(0.662002687 * s1 + 0.684122060 * s2 - 0.323583601 * s3 - 0.0225411470 * c, 0.0, 1.0);
}

vec3 tonemap(vec3 x) {
  // ACES fitted (Narkowicz).
  x *= uExposure;
  return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

float ggx(float NdotH, float a) {
  float a2 = a * a;
  float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
  return a2 / (PI * d * d + 1e-7);
}

float smithG(float NdotV, float NdotL, float a) {
  float k = (a + 1.0) * (a + 1.0) / 8.0;
  float gv = NdotV / (NdotV * (1.0 - k) + k);
  float gl = NdotL / (NdotL * (1.0 - k) + k);
  return gv * gl;
}

// Procedural studio environment in camera space: a bright overhead softbox, a side strip
// light, a warm floor bounce and a soft gradient. Gives metals believable reflections.
vec3 studioEnv(vec3 R, float rough) {
  vec3 r = vec3(dot(R, uCamRight), dot(R, uCamUp), -dot(R, uCamForward));
  float blur = 0.04 + rough * 0.9;
  vec3 c = mix(uGroundColor * 0.6, uSkyColor * 1.25, smoothstep(-0.35, 0.55, r.y));
  // Overhead softbox.
  float box1 = smoothstep(0.80 - blur * 0.5, 0.88 + blur * 0.1, r.y) * (1.0 - smoothstep(0.55, 0.95 + blur, abs(r.x)));
  // Key-side strip (upper left).
  vec2 sd = vec2(r.x + 0.55, r.y - 0.35);
  float strip = 1.0 - smoothstep(0.10, 0.10 + blur * 1.2 + 0.08, abs(sd.x));
  strip *= 1.0 - smoothstep(0.35, 0.45 + blur, abs(sd.y));
  strip *= step(0.0, r.z + 0.4);
  // Rim light from behind.
  float rim = smoothstep(0.75 - blur, 0.97, -r.z) * 0.6;
  float lum = (box1 * 3.2 + strip * 2.4 + rim) / (1.0 + rough * 2.5);
  return c + vec3(lum);
}

vec3 thicknessColor(float t) {
  // red -> orange -> yellow -> green -> cyan -> blue
  vec3 c0 = vec3(0.86, 0.10, 0.08);
  vec3 c1 = vec3(0.96, 0.45, 0.06);
  vec3 c2 = vec3(0.98, 0.86, 0.12);
  vec3 c3 = vec3(0.35, 0.80, 0.30);
  vec3 c4 = vec3(0.20, 0.70, 0.86);
  vec3 c5 = vec3(0.16, 0.30, 0.86);
  t = clamp(t, 0.0, 1.0) * 5.0;
  if (t < 1.0) return mix(c0, c1, t);
  if (t < 2.0) return mix(c1, c2, t - 1.0);
  if (t < 3.0) return mix(c2, c3, t - 2.0);
  if (t < 4.0) return mix(c3, c4, t - 3.0);
  return mix(c4, c5, t - 4.0);
}

vec3 toLin(vec3 c) { return c * (c * (c * 0.305306011 + 0.682171111) + 0.012522878); }

vec3 shade(vec3 N, vec3 V, vec3 base, float rough, float metal, float clearcoat) {
  float a = max(rough * rough, 0.0025);
  vec3 diffuse = base * (1.0 - metal);
  vec3 F0 = mix(vec3(0.04), base, metal);
  float NdotV = max(dot(N, V), 1e-4);
  vec3 col = vec3(0.0);
  for (int i = 0; i < 3; ++i) {
    vec3 L = uLightDir[i];
    float NdotL = dot(N, L);
    if (NdotL <= 0.0) continue;
    vec3 H = normalize(L + V);
    float NdotH = max(dot(N, H), 0.0);
    float VdotH = max(dot(V, H), 0.0);
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - VdotH, 5.0);
    vec3 spec = ggx(NdotH, a) * smithG(NdotV, NdotL, a) * F / (4.0 * NdotV * NdotL + 1e-4);
    col += (diffuse * (1.0 - F) + spec * PI) * uLightColor[i] * NdotL;
  }
  // Hemisphere ambient (world up = +Z).
  vec3 amb = mix(uGroundColor, uSkyColor, N.z * 0.5 + 0.5);
  col += diffuse * amb;
  // Environment reflection.
  vec3 R = reflect(-V, N);
  vec3 Fr = F0 + (max(vec3(1.0 - rough), F0) - F0) * pow(1.0 - NdotV, 5.0);
  col += studioEnv(R, rough) * Fr * uEnvStrength;
  if (clearcoat > 0.0) {
    float Fc = 0.04 + 0.96 * pow(1.0 - NdotV, 5.0);
    col += studioEnv(R, 0.05) * Fc * clearcoat * uEnvStrength;
  }
  return col;
}

void main() {
  int flags = int(vMaterial.z + 0.5);
  vec3 V = uOrtho == 1 ? -uCamForward : normalize(uCamPos - vWorldPos);
#ifdef CLIP
  float d = dot(vWorldPos, uClipPlane.xyz) + uClipPlane.w;
  if (d > 0.0) discard;
  if (uPass == 0) {
    // Facing with respect to the part's real outside (inside-out parts are flagged).
    bool front = gl_FrontFacing != ((flags & 32) != 0);
    if (uCapPass == 0 && !front) discard;
    if (uCapPass == 1) {
      if (front) discard;
      // Interior visible through the cut: shade as a section cap lying on the plane.
      vec3 cap = vCapColor;
      if ((flags & 2) != 0) cap = mix(cap, uSelectColor, 0.35);
      float denom = dot(V, uClipPlane.xyz);
      if (uHatch == 1 && denom > 1e-5) {
        vec3 p = vWorldPos + V * (-d / denom);
        vec3 n = uClipPlane.xyz;
        vec3 u = normalize(abs(n.z) < 0.9 ? cross(n, vec3(0.0, 0.0, 1.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
        vec3 w = cross(n, u);
        float s = (dot(p, u) + dot(p, w)) / uHatchSpacing;
        float fw = max(fwidth(s), 1e-4);
        float f = fract(s);
        float distToLine = min(f, 1.0 - f);
        float line = 1.0 - smoothstep(0.09 - fw, 0.09 + fw, distToLine);
        float fade = 1.0 - smoothstep(0.25, 0.6, fw);
        cap = mix(cap, cap * 0.38, line * fade);
      }
      vec3 lit = cap * (0.62 + 0.18 * max(denom, 0.0));
      fragColor = vec4(toSrgb(tonemap(lit)), 1.0);
      return;
    }
  }
#endif
  vec3 N = normalize(vNormal);
  if (!gl_FrontFacing) N = -N;
  vec3 base = vColor.rgb;
  float rough = vMaterial.x;
  float metal = vMaterial.y;
  float clearcoat = vMaterial.w;
  if (uMode == 1 || uMode == 2) {
    float range = max(uScalarRange.y - uScalarRange.x, 1e-6);
    if (uMode == 1) base = toLin(thicknessColor((vScalar - uScalarRange.x) / range));
    else base = vScalar < uScalarRange.z ? toLin(vec3(0.90, 0.12, 0.10)) : toLin(vec3(0.78, 0.78, 0.76));
    rough = 0.55; metal = 0.0; clearcoat = 0.0;
  } else if (uMode == 3) {
    float nz = N.z;
    bool bed = vWorldPos.z < uBedZ;
    if (nz < -uOverhangSin && !bed) {
      float k = smoothstep(uOverhangSin, 1.0, -nz);
      base = mix(toLin(vec3(0.95, 0.45, 0.10)), toLin(vec3(0.85, 0.08, 0.10)), k);
    } else {
      base = toLin(vec3(0.75, 0.76, 0.78));
    }
    rough = 0.6; metal = 0.0; clearcoat = 0.0;
  } else if (uMode == 4) {
    base = toLin(N * 0.5 + 0.5);
    metal = 0.0;
  }
  vec3 col = shade(N, V, base, rough, metal, clearcoat);
  if ((flags & 2) != 0) {
    float fres = pow(1.0 - max(dot(N, V), 0.0), 2.0);
    col = mix(col, uSelectColor * 0.9, 0.28) + uSelectColor * fres * 0.9;
  }
  if ((flags & 8) != 0) col += uSelectColor * 0.25;
  vec3 outc = toSrgb(tonemap(col));
  if (uPass == 1) {
    float fres = pow(1.0 - max(dot(N, V), 0.0), 1.5);
    float alpha = 0.10 + 0.55 * fres;
    fragColor = vec4(outc * alpha, alpha);
  } else {
    fragColor = vec4(outc, 1.0);
  }
}
)";

const char* kDepthVS = R"(
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
layout(location = 3) in uint aPart;
uniform mat4 uViewProj;
)" FSV_PART_LOOKUP R"(
void main() {
  vec4 t1 = partTexel(aPart, 1);
  int flags = int(t1.w + 0.5);
  if ((flags & 1) == 0 || (flags & 16) != 0) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
  gl_Position = uViewProj * vec4(aPos + t1.xyz, 1.0);
}
)";

const char* kDepthFS = R"(
precision mediump float;
out vec4 fragColor;
void main() { fragColor = vec4(1.0); }
)";

const char* kLineVS = R"(
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
layout(location = 3) in uint aPart;
uniform mat4 uViewProj;
uniform float uDepthBias;
)" FSV_PART_LOOKUP R"(
out vec3 vWorldPos;
void main() {
  vec4 t1 = partTexel(aPart, 1);
  int flags = int(t1.w + 0.5);
  if ((flags & 1) == 0) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
  vec3 wp = aPos + t1.xyz;
  vWorldPos = wp;
  vec4 p = uViewProj * vec4(wp, 1.0);
  p.z -= uDepthBias * p.w;
  gl_Position = p;
}
)";

const char* kLineFS = R"(
precision highp float;
in vec3 vWorldPos;
uniform vec4 uColor;
uniform vec4 uClipPlane;
uniform int uClip;
out vec4 fragColor;
void main() {
  if (uClip == 1 && dot(vWorldPos, uClipPlane.xyz) + uClipPlane.w > 0.0) discard;
  fragColor = vec4(uColor.rgb * uColor.a, uColor.a);
}
)";

const char* kGridVS = R"(
precision highp float;
layout(location = 0) in vec2 aPos;  // unit quad [-1,1]
uniform mat4 uViewProj;
uniform vec3 uCenter;
uniform float uExtent;
out vec3 vWorldPos;
void main() {
  vec3 wp = vec3(uCenter.xy + aPos * uExtent, uCenter.z);
  vWorldPos = wp;
  gl_Position = uViewProj * vec4(wp, 1.0);
}
)";

const char* kGridFS = R"(
precision highp float;
in vec3 vWorldPos;
uniform vec3 uCenter;
uniform float uExtent;
uniform float uStep;
uniform vec3 uLineColor;
uniform float uLineAlpha;
uniform float uFloorAlpha;
uniform vec3 uFloorColor;
uniform int uShadows;
uniform float uShadowStrength;
uniform mat4 uLightViewProj;
uniform highp sampler2DShadow uShadowMap;
uniform float uShadowTexel;
uniform vec3 uCamPos;
uniform int uAxes;
out vec4 fragColor;

float gridLine(vec2 coord, float step, float width) {
  vec2 c = coord / step;
  vec2 fw = max(fwidth(c), vec2(1e-5));
  vec2 g = abs(fract(c - 0.5) - 0.5) / fw;
  float line = 1.0 - min(min(g.x, g.y) / width, 1.0);
  // Fade lines that get denser than a few pixels.
  float density = max(fw.x, fw.y);
  return line * (1.0 - smoothstep(0.12, 0.45, density));
}

float shadowAt(vec3 p) {
  vec4 lp = uLightViewProj * vec4(p, 1.0);
  vec3 s = lp.xyz / lp.w * 0.5 + 0.5;
  if (s.x <= 0.0 || s.x >= 1.0 || s.y <= 0.0 || s.y >= 1.0 || s.z >= 1.0) return 1.0;
  float sum = 0.0;
  const vec2 taps[12] = vec2[12](
    vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
    vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893),
    vec2(0.507, 0.064), vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598));
  float r = uShadowTexel * 3.5;
  for (int i = 0; i < 12; ++i) sum += texture(uShadowMap, vec3(s.xy + taps[i] * r, s.z - 0.0015));
  return sum / 12.0;
}

void main() {
  vec2 rel = vWorldPos.xy - uCenter.xy;
  float r = length(rel) / uExtent;
  float fade = 1.0 - smoothstep(0.35, 1.0, r);
  // Grazing-angle fade to avoid moire near the horizon.
  vec3 v = normalize(uCamPos - vWorldPos);
  float graze = smoothstep(0.02, 0.25, abs(v.z));
  float minor = gridLine(vWorldPos.xy, uStep, 1.0);
  float major = gridLine(vWorldPos.xy, uStep * 10.0, 1.4);
  float lines = max(minor * 0.45, major) * uLineAlpha * fade * graze;
  vec3 color = uLineColor;
  float alpha = lines;
  if (uAxes == 1) {
    vec2 fw = max(fwidth(vWorldPos.xy), vec2(1e-5));
    float ax = 1.0 - min(abs(vWorldPos.y) / (fw.y * 1.5), 1.0);
    float ay = 1.0 - min(abs(vWorldPos.x) / (fw.x * 1.5), 1.0);
    if (ax > 0.0) { color = mix(color, vec3(0.92, 0.32, 0.32), ax); alpha = max(alpha, ax * 0.8 * fade); }
    if (ay > 0.0) { color = mix(color, vec3(0.40, 0.80, 0.40), ay); alpha = max(alpha, ay * 0.8 * fade); }
  }
  // Floor tint.
  vec3 premul = color * alpha + uFloorColor * uFloorAlpha * fade * (1.0 - alpha);
  float a = alpha + uFloorAlpha * fade * (1.0 - alpha);
  if (uShadows == 1) {
    float vis = shadowAt(vWorldPos);
    float sh = (1.0 - vis) * uShadowStrength * (1.0 - smoothstep(0.5, 1.0, r));
    premul = premul * (1.0 - sh);
    a = a + sh * (1.0 - a);
  }
  fragColor = vec4(premul, a);
}
)";

const char* kBackgroundVS = R"(
precision highp float;
out vec2 vUv;
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char* kBackgroundFS = R"(
precision highp float;
in vec2 vUv;
uniform vec3 uTop;
uniform vec3 uBottom;
uniform float uVignette;
out vec4 fragColor;
void main() {
  vec3 c = mix(uBottom, uTop, smoothstep(0.0, 1.0, vUv.y));
  vec2 d = vUv - vec2(0.5, 0.55);
  c *= 1.0 - uVignette * dot(d, d) * 1.6;
  // Tiny dither against banding.
  float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
  c += (n - 0.5) / 255.0;
  fragColor = vec4(c, 1.0);
}
)";

const char* kPlaneVS = R"(
precision highp float;
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }
)";

const char* kPlaneFS = R"(
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() { fragColor = vec4(uColor.rgb * uColor.a, uColor.a); }
)";

}  // namespace shaders

namespace {

GLuint compile(GLenum type, const char* src, const std::string& defines, const char* name) {
    std::string full = "#version 300 es\n" + defines + "\n" + src;
    GLuint s = glCreateShader(type);
    const char* p = full.c_str();
    glShaderSource(s, 1, &p, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetShaderiv(s, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log((size_t)std::max(len, 1));
        glGetShaderInfoLog(s, len, nullptr, log.data());
        FSV_LOGE("%s %s shader failed: %s", name, type == GL_VERTEX_SHADER ? "vertex" : "fragment", log.data());
        glDeleteShader(s);
        return 0;
    }
    return s;
}

}  // namespace

GLuint buildProgram(const char* vs, const char* fs, const std::string& defines, const char* name) {
    GLuint v = compile(GL_VERTEX_SHADER, vs, defines, name);
    GLuint f = compile(GL_FRAGMENT_SHADER, fs, defines, name);
    if (!v || !f) {
        if (v) glDeleteShader(v);
        if (f) glDeleteShader(f);
        return 0;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetProgramiv(p, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log((size_t)std::max(len, 1));
        glGetProgramInfoLog(p, len, nullptr, log.data());
        FSV_LOGE("%s link failed: %s", name, log.data());
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

}  // namespace fsv
