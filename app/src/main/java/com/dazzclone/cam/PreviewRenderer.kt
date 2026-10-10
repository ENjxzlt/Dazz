package com.dazzclone.cam

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders camera frames through [FilmShader] into a GLSurfaceView.
 *
 * Frames come from a CameraX [ImageAnalysis] use case as upright RGBA images
 * (CameraX does the rotation), are copied in [analyze] and uploaded as a plain
 * 2D texture on the GL thread. No SurfaceTexture is involved, so the camera
 * stream survives GL context loss and needs no rotation math here.
 */
class PreviewRenderer(
    private val view: GLSurfaceView,
    /** Called on the GL thread when the renderer cannot run (e.g. shader compile error). */
    private val onError: (Throwable) -> Unit,
    /** Called on the analysis thread for every frame received. */
    private val onFrame: () -> Unit,
) : GLSurfaceView.Renderer, ImageAnalysis.Analyzer {

    @Volatile var camera: FilmCamera = CAMERAS[0]
    @Volatile var mirror = false

    private var shader: FilmShader? = null
    private var texture = 0
    private var texW = 0
    private var texH = 0
    private var viewW = 1
    private var viewH = 1
    private var frameCount = 0

    // Texture rows start at the image top; flip so the image top lands at the screen top.
    private val flipY = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    // Latest frame, written by the analysis thread and read by the GL thread.
    private val lock = Any()
    private var frame: ByteBuffer? = null
    private var frameW = 0
    private var frameH = 0
    private var frameDirty = false

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val rowBytes = w * 4
            val src = plane.buffer
            synchronized(lock) {
                var buf = frame
                if (buf == null || buf.capacity() != rowBytes * h) {
                    buf = ByteBuffer.allocateDirect(rowBytes * h).order(ByteOrder.nativeOrder())
                    frame = buf
                }
                buf!!.clear()
                if (plane.rowStride == rowBytes) {
                    src.rewind()
                    src.limit(rowBytes * h)
                    buf.put(src)
                } else {
                    for (row in 0 until h) {
                        src.limit(row * plane.rowStride + rowBytes)
                        src.position(row * plane.rowStride)
                        buf.put(src)
                    }
                }
                buf.flip()
                frameW = w
                frameH = h
                frameDirty = true
            }
        } finally {
            image.close()
        }
        onFrame()
        view.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            shader = FilmShader()
        } catch (e: Throwable) {
            shader = null
            onError(e)
            return
        }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // New context: the texture is empty, force a full upload of the next frame.
        texW = 0
        texH = 0
        synchronized(lock) { if (frame != null) frameDirty = true }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val sh = shader ?: return

        synchronized(lock) {
            val buf = frame
            if (frameDirty && buf != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                if (frameW != texW || frameH != texH) {
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameW, frameH, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
                    )
                    texW = frameW
                    texH = frameH
                } else {
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0, frameW, frameH,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
                    )
                }
                frameDirty = false
            }
        }
        if (texW == 0) return

        val (crop, _) = cropFor(texW, texH, camera.ratio)
        // Let the light leak drift slowly so the preview feels alive.
        val t = SystemClock.elapsedRealtime() / 4000.0
        val lx = 0.5f + 0.4f * cos(t).toFloat()
        val ly = 0.5f + 0.4f * sin(t * 0.7).toFloat()

        sh.draw(
            GLES20.GL_TEXTURE_2D, texture, flipY,
            viewW, viewH, crop, rotation = 0, mirror = mirror, cam = camera,
            seed = (frameCount++ % 1000) / 1000f, leakX = lx, leakY = ly,
        )
    }
}
