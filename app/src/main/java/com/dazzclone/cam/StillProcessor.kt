package com.dazzclone.cam

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs [FilmShader] over a full-resolution bitmap in an offscreen EGL
 * context. Not thread-safe: create, use and [release] on one thread.
 */
class StillProcessor {

    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface
    private val shader: FilmShader
    val maxSize: Int

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No EGL config"
        }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        // Rendering goes into an FBO; the pbuffer only exists to make the context current.
        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        val maxRb = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, maxRb, 0)
        maxSize = minOf(max[0], maxRb[0])
        shader = FilmShader(external = false)
    }

    /**
     * Applies [cam] to the upright [src] bitmap: centre-crops to the camera
     * ratio and returns a new bitmap. [src] must fit within [maxSize].
     */
    fun process(src: Bitmap, cam: FilmCamera, seed: Float, leakX: Float, leakY: Float): Bitmap {
        val (crop, size) = cropFor(src.width, src.height, cam.ratio)
        val outW = size[0]
        val outH = size[1]

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        val srcTex = ids[0]
        val dstTex = ids[1]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        setParams()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dstTex)
        setParams()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, dstTex, 0,
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Framebuffer incomplete"
        }

        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        // The bitmap's first row lands at t = 0 and glReadPixels returns the
        // bottom row first, so the two flips cancel: no y-flip needed here.
        shader.draw(
            GLES20.GL_TEXTURE_2D, srcTex, identity, outW, outH, crop,
            rotation = 0, mirror = false, cam = cam, seed = seed, leakX = leakX, leakY = leakY,
        )

        val buf = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        GLES20.glDeleteTextures(2, ids, 0)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.copyPixelsFromBuffer(buf)
        return out
    }

    private fun setParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun release() {
        shader.release()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        // No eglTerminate: the default display is shared with the GLSurfaceView.
    }
}
