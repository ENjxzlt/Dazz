package com.dazzclone.cam

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The whole film look in one fragment-shader pass. Compiled in two flavours:
 * [external] = true samples the live camera SurfaceTexture (OES texture),
 * false samples a regular 2D texture holding a still bitmap.
 *
 * Coordinate model: `v_uv` is the output position (0..1). It is mapped into
 * the crop rectangle of the upright image, then rotated back into buffer
 * space ([setRotation]) and finally through the SurfaceTexture matrix.
 */
class FilmShader(external: Boolean) {

    private val program: Int
    private val loc = HashMap<String, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val aPos: Int

    init {
        val header = if (external) {
            "#extension GL_OES_EGL_image_external : require\nprecision highp float;\nuniform samplerExternalOES u_tex;\n"
        } else {
            "precision highp float;\nuniform sampler2D u_tex;\n"
        }
        program = link(VERT, header + FRAG)
        aPos = GLES20.glGetAttribLocation(program, "a_pos")
    }

    private fun u(name: String): Int =
        loc.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    /**
     * Draws [texture] into the currently bound framebuffer/viewport of size
     * [width] x [height].
     *
     * @param crop x, y, w, h in upright-image uv (y measured from the top for
     *   stills, from the bottom for the live preview — both match their source)
     * @param rotation clockwise degrees the buffer must be rotated to be upright
     */
    fun draw(
        texTarget: Int,
        texture: Int,
        texMatrix: FloatArray,
        width: Int,
        height: Int,
        crop: FloatArray,
        rotation: Int,
        mirror: Boolean,
        cam: FilmCamera,
        seed: Float,
        leakX: Float,
        leakY: Float,
    ) {
        GLES20.glUseProgram(program)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(texTarget, texture)
        GLES20.glUniform1i(u("u_tex"), 0)

        GLES20.glUniformMatrix4fv(u("u_texMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform2f(u("u_res"), width.toFloat(), height.toFloat())
        GLES20.glUniform4f(u("u_crop"), crop[0], crop[1], crop[2], crop[3])
        GLES20.glUniform1f(u("u_rot"), (((rotation % 360) + 360) % 360).toFloat())
        GLES20.glUniform1f(u("u_mirror"), if (mirror) 1f else 0f)
        GLES20.glUniform1f(u("u_seed"), seed)
        GLES20.glUniform1f(u("u_scale"), height / 1000f)

        GLES20.glUniform1f(u("u_exposure"), cam.exposure)
        GLES20.glUniform1f(u("u_contrast"), cam.contrast)
        GLES20.glUniform1f(u("u_saturation"), cam.saturation)
        GLES20.glUniform1f(u("u_temp"), cam.temp)
        GLES20.glUniform1f(u("u_tint"), cam.tint)
        GLES20.glUniform1f(u("u_fade"), cam.fade)
        GLES20.glUniform3fv(u("u_shadow"), 1, cam.shadow, 0)
        GLES20.glUniform3fv(u("u_highlight"), 1, cam.highlight, 0)
        GLES20.glUniform1f(u("u_vignette"), cam.vignette)
        GLES20.glUniform1f(u("u_grain"), cam.grain)
        GLES20.glUniform1f(u("u_chroma"), cam.chroma)
        GLES20.glUniform1f(u("u_blur"), cam.blur)
        GLES20.glUniform1f(u("u_halation"), cam.halation)
        GLES20.glUniform1f(u("u_pixel"), cam.pixel)
        GLES20.glUniform1f(u("u_posterize"), cam.posterize)
        GLES20.glUniform1f(u("u_scan"), cam.scan)
        GLES20.glUniform1f(u("u_mono"), cam.mono)
        GLES20.glUniform1f(u("u_leak"), cam.leak)
        GLES20.glUniform3fv(u("u_leakColor"), 1, cam.leakColor, 0)
        GLES20.glUniform2f(u("u_leakPos"), leakX, leakY)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun release() = GLES20.glDeleteProgram(program)

    companion object {
        private const val VERT = """
attribute vec2 a_pos;
varying vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}"""

        private const val FRAG = """
varying vec2 v_uv;
uniform mat4 u_texMatrix;
uniform vec2 u_res;
uniform vec4 u_crop;
uniform float u_rot;
uniform float u_mirror;
uniform float u_seed;
uniform float u_scale;

uniform float u_exposure, u_contrast, u_saturation, u_temp, u_tint, u_fade;
uniform vec3 u_shadow, u_highlight;
uniform float u_vignette, u_grain, u_chroma, u_blur, u_halation;
uniform float u_pixel, u_posterize, u_scan, u_mono, u_leak;
uniform vec3 u_leakColor;
uniform vec2 u_leakPos;

float hash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

vec3 tex(vec2 uv) {
  vec2 d = u_crop.xy + clamp(uv, 0.0, 1.0) * u_crop.zw;
  // Upright image coords -> buffer coords (inverse of the clockwise rotation).
  vec2 b = d;
  if (u_rot > 45.0 && u_rot < 135.0) b = vec2(1.0 - d.y, d.x);
  else if (u_rot >= 135.0 && u_rot < 225.0) b = 1.0 - d;
  else if (u_rot >= 225.0) b = vec2(d.y, 1.0 - d.x);
  return texture2D(u_tex, (u_texMatrix * vec4(b, 0.0, 1.0)).xy).rgb;
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
  vec2 uv = v_uv;
  if (u_mirror > 0.5) uv.x = 1.0 - uv.x;
  vec2 px = 1.0 / u_res;

  if (u_pixel > 0.0) {
    vec2 block = px * u_pixel * max(1.0, u_scale);
    uv = (floor(uv / block) + 0.5) * block;
  }

  if (u_scan > 0.0) {
    float line = floor(uv.y * 240.0);
    uv.x += (hash(vec2(line, u_seed)) - 0.5) * 0.004 * u_scan;
  }

  vec3 c;
  if (u_blur > 0.0) {
    vec2 r = px * u_blur * max(1.0, u_scale);
    c = tex(uv) * 0.4
      + (tex(uv + vec2(r.x, 0.0)) + tex(uv - vec2(r.x, 0.0))
       + tex(uv + vec2(0.0, r.y)) + tex(uv - vec2(0.0, r.y))) * 0.15;
  } else {
    c = tex(uv);
  }
  if (u_chroma > 0.0) {
    vec2 off = (uv - 0.5) * u_chroma * 0.012;
    c.r = tex(uv + off).r;
    c.b = tex(uv - off).b;
  }

  if (u_halation > 0.0) {
    vec3 glow = vec3(0.0);
    for (int i = 0; i < 8; i++) {
      float a = float(i) * 0.785398;
      vec3 s = tex(uv + vec2(cos(a), sin(a)) * 0.012);
      glow += s * smoothstep(0.65, 1.0, luma(s));
    }
    c += glow / 8.0 * vec3(1.0, 0.35, 0.15) * u_halation * 1.5;
  }

  c *= pow(2.0, u_exposure);
  c.r += u_temp * 0.08;
  c.b -= u_temp * 0.08;
  c.g += u_tint * 0.06;
  c = (c - 0.5) * u_contrast + 0.5;
  float l = luma(c);
  c = mix(vec3(l), c, u_saturation);
  c += u_shadow * (1.0 - l) + u_highlight * l;
  c = mix(c, vec3(luma(c)), u_mono);
  c = clamp(c, 0.0, 1.0);
  c = c * (1.0 - u_fade) + u_fade;

  if (u_posterize > 0.0) c = floor(c * u_posterize + 0.5) / u_posterize;

  if (u_leak > 0.0) {
    float d = distance(v_uv, u_leakPos);
    vec3 leak = u_leakColor * u_leak * smoothstep(0.9, 0.0, d);
    c = 1.0 - (1.0 - c) * (1.0 - leak);
  }

  if (u_scan > 0.0) {
    c *= 1.0 - u_scan * 0.18 * (0.5 + 0.5 * sin(gl_FragCoord.y / max(1.0, u_scale) * 3.14159));
  }

  float v = smoothstep(0.85, 0.25, length(v_uv - 0.5) * 1.25);
  c *= mix(1.0, v, u_vignette);

  if (u_grain > 0.0) {
    vec2 g = floor(gl_FragCoord.xy / max(1.0, u_scale));
    float n = hash(g + u_seed * 17.0) - 0.5;
    float lm = luma(c);
    c += n * u_grain * (1.0 - abs(lm - 0.5) * 1.2) * 1.6;
  }

  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""

        private fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(sh)
                GLES20.glDeleteShader(sh)
                throw IllegalStateException("Shader compile failed: $log")
            }
            return sh
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("Link failed: ${GLES20.glGetProgramInfoLog(p)}")
            return p
        }
    }
}

/**
 * Centre crop of a [srcW] x [srcH] upright image to the camera ratio
 * (portrait width/height; landscape sources use the inverse).
 * Returns normalised [x, y, w, h] and the output size in pixels.
 */
fun cropFor(srcW: Int, srcH: Int, ratio: Float): Pair<FloatArray, IntArray> {
    val target = if (srcW > srcH) 1f / ratio else ratio
    val src = srcW.toFloat() / srcH
    var w = 1f
    var h = 1f
    if (src > target) w = target / src else h = src / target
    return floatArrayOf((1f - w) / 2f, (1f - h) / 2f, w, h) to
        intArrayOf(Math.round(srcW * w), Math.round(srcH * h))
}
