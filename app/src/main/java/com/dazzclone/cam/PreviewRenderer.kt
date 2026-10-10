package com.dazzclone.cam

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the CameraX preview stream through [FilmShader] into a GLSurfaceView.
 * All methods except [provideSurface] and the volatile setters run on the GL thread.
 */
class PreviewRenderer(
    private val view: GLSurfaceView,
    /** Called on the GL thread once a fresh SurfaceTexture exists (incl. after context loss). */
    private val onSurfaceReady: () -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile var camera: FilmCamera = CAMERAS[0]
    @Volatile var mirror = false

    private var shader: FilmShader? = null
    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private var viewW = 1
    private var viewH = 1
    private var frame = 0

    // Written from the camera executor, read on the GL thread.
    @Volatile private var bufferW = 0
    @Volatile private var bufferH = 0
    @Volatile private var rotation = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        shader = FilmShader(external = true)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture?.release()
        surfaceTexture = SurfaceTexture(oesTexture).apply {
            setOnFrameAvailableListener { view.requestRender() }
        }
        bufferW = 0
        onSurfaceReady()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (bufferW == 0) return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        val rot = rotation
        val (uprightW, uprightH) = if (rot % 180 == 90) bufferH to bufferW else bufferW to bufferH
        val (crop, _) = cropFor(uprightW, uprightH, camera.ratio)

        // Let the light leak drift slowly so the preview feels alive.
        val t = SystemClock.elapsedRealtime() / 4000.0
        val lx = 0.5f + 0.4f * cos(t).toFloat()
        val ly = 0.5f + 0.4f * sin(t * 0.7).toFloat()

        shader?.draw(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, texMatrix,
            viewW, viewH, crop, rot, mirror, camera,
            seed = (frame++ % 1000) / 1000f, leakX = lx, leakY = ly,
        )
    }

    /** CameraX surface provider callback; hops onto the GL thread. */
    fun provideSurface(request: SurfaceRequest, executor: Executor) {
        view.queueEvent {
            val st = surfaceTexture
            if (st == null) {
                request.willNotProvideSurface()
                return@queueEvent
            }
            val res = request.resolution
            st.setDefaultBufferSize(res.width, res.height)
            request.setTransformationInfoListener(executor) { info ->
                rotation = info.rotationDegrees
            }
            val surface = Surface(st)
            request.provideSurface(surface, executor) { surface.release() }
            bufferW = res.width
            bufferH = res.height
        }
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        shader?.release()
        shader = null
    }
}
