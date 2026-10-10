package com.dazzclone.cam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.MediaActionSound
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: PreviewRenderer
    private lateinit var viewfinder: AspectFrameLayout
    private lateinit var dateOverlay: TextView
    private lateinit var countdownView: TextView
    private lateinit var flashOverlay: View
    private lateinit var cameraName: TextView
    private lateinit var picker: LinearLayout
    private lateinit var lastPhoto: ImageView
    private lateinit var flashButton: TextView
    private lateinit var timerButton: TextView
    private lateinit var dateButton: TextView

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val sound = MediaActionSound()

    /** Single thread that owns the offscreen EGL context for photo processing. */
    private val processing: ExecutorService = Executors.newSingleThreadExecutor()
    private var stillProcessor: StillProcessor? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var camera = CAMERAS[0]
    private var flashMode = FLASH_OFF
    private var timerSeconds = 0
    private var dateEnabled = true
    private var busy = false
    private var lastUri: Uri? = null

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) bindCamera()
            else Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) importPhoto(uri)
        }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture?.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.glView)
        viewfinder = findViewById(R.id.viewfinder)
        dateOverlay = findViewById(R.id.dateOverlay)
        countdownView = findViewById(R.id.countdown)
        flashOverlay = findViewById(R.id.flashOverlay)
        cameraName = findViewById(R.id.cameraName)
        picker = findViewById(R.id.picker)
        lastPhoto = findViewById(R.id.lastPhoto)
        flashButton = findViewById(R.id.flashButton)
        timerButton = findViewById(R.id.timerButton)
        dateButton = findViewById(R.id.dateButton)

        lastPhoto.outlineProvider = ViewOutlineProvider.BACKGROUND
        lastPhoto.clipToOutline = true

        renderer = PreviewRenderer(glView) { main.post { bindCamera() } }
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        lensFacing = prefs.getInt("lens", CameraSelector.LENS_FACING_BACK)
        flashMode = prefs.getInt("flash", FLASH_OFF)
        timerSeconds = prefs.getInt("timer", 0)
        val savedCam = CAMERAS.firstOrNull { it.id == prefs.getString("camera", null) } ?: CAMERAS[0]
        dateEnabled = prefs.getBoolean("date", savedCam.date)

        buildPicker()
        selectCamera(savedCam, resetDate = false)
        updateToggles()

        findViewById<View>(R.id.shutter).setOnClickListener { onShutter(it) }
        findViewById<View>(R.id.flipButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            prefs.edit().putInt("lens", lensFacing).apply()
            bindCamera()
        }
        flashButton.setOnClickListener {
            flashMode = (flashMode + 1) % 3
            prefs.edit().putInt("flash", flashMode).apply()
            updateToggles()
            applyFlashMode()
        }
        timerButton.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            prefs.edit().putInt("timer", timerSeconds).apply()
            updateToggles()
        }
        dateButton.setOnClickListener {
            dateEnabled = !dateEnabled
            prefs.edit().putBoolean("date", dateEnabled).apply()
            updateToggles()
        }
        lastPhoto.setOnClickListener {
            val uri = lastUri ?: return@setOnClickListener
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }
        }
        findViewById<View>(R.id.importButton).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        sound.load(MediaActionSound.SHUTTER_CLICK)
        refreshThumbnail()
        if (!hasCameraPermission()) requestPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
        processing.execute { stillProcessor?.release() }
        processing.shutdown()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---- Camera ----------------------------------------------------------

    /** (Re)binds preview + capture. Called whenever the GL surface or lens changes. */
    private fun bindCamera() {
        if (!hasCameraPermission() || isFinishing) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider

            var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!provider.hasCamera(selector)) {
                lensFacing = CameraSelector.LENS_FACING_BACK
                selector = CameraSelector.DEFAULT_BACK_CAMERA
            }
            renderer.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT

            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            // We mirror the front camera ourselves in the shader, so the stream must not be.
            val preview = Preview.Builder()
                .setResolutionSelector(resolution)
                .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
                .build()
            val executor = ContextCompat.getMainExecutor(this)
            preview.setSurfaceProvider(executor) { request -> renderer.provideSurface(request, executor) }

            val capture = ImageCapture.Builder()
                .setResolutionSelector(resolution)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            applyFlashMode()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun isFront() = lensFacing == CameraSelector.LENS_FACING_FRONT

    private fun applyFlashMode() {
        // The front camera uses a screen flash instead (see takePhoto).
        imageCapture?.flashMode = if (isFront()) ImageCapture.FLASH_MODE_OFF else when (flashMode) {
            FLASH_ON -> ImageCapture.FLASH_MODE_ON
            FLASH_AUTO -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    private fun onShutter(v: View) {
        if (busy || imageCapture == null) return
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (timerSeconds == 0) {
            takePhoto()
            return
        }
        busy = true
        var remaining = timerSeconds
        countdownView.visibility = View.VISIBLE
        val tick = object : Runnable {
            override fun run() {
                if (remaining == 0) {
                    countdownView.visibility = View.GONE
                    busy = false
                    takePhoto()
                    return
                }
                countdownView.text = remaining.toString()
                remaining--
                main.postDelayed(this, 1000)
            }
        }
        tick.run()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        busy = true
        // Snapshot settings now; the user may switch camera while we develop.
        val cam = camera
        val stamp = dateEnabled
        val front = isFront()
        val screenFlash = front && flashMode != FLASH_OFF
        val shoot = {
            sound.play(MediaActionSound.SHUTTER_CLICK)
            capture.takePicture(processing, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    main.post { if (screenFlash) endScreenFlash() }
                    val upright = try {
                        uprightBitmap(image, mirror = front)
                    } finally {
                        image.close()
                    }
                    developAndSave(upright, cam, stamp)
                }

                override fun onError(exception: ImageCaptureException) {
                    main.post {
                        if (screenFlash) endScreenFlash()
                        busy = false
                        Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        if (screenFlash) {
            setScreenBrightness(1f)
            flashOverlay.animate().alpha(1f).setDuration(120).withEndAction { main.postDelayed(shoot, 250) }.start()
        } else {
            flashOverlay.alpha = 0.6f
            flashOverlay.animate().alpha(0f).setDuration(180).start()
            shoot()
        }
    }

    private fun endScreenFlash() {
        flashOverlay.animate().alpha(0f).setDuration(250).start()
        setScreenBrightness(-1f)
    }

    private fun setScreenBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    // ---- Developing ------------------------------------------------------

    /** Runs on [processing]. Rotates to upright, mirrors the selfie camera, caps size. */
    private fun uprightBitmap(image: ImageProxy, mirror: Boolean): Bitmap {
        val src = image.toBitmap()
        val m = Matrix()
        m.postRotate(image.imageInfo.rotationDegrees.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        val maxSize = processor().maxSize.coerceAtMost(MAX_OUTPUT)
        val longEdge = maxOf(src.width, src.height)
        if (longEdge > maxSize) {
            val s = maxSize.toFloat() / longEdge
            m.postScale(s, s)
        }
        if (m.isIdentity) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
            if (it !== src) src.recycle()
        }
    }

    private fun processor(): StillProcessor =
        stillProcessor ?: StillProcessor().also { stillProcessor = it }

    /** Runs on [processing]: shader pass, date stamp, frame, MediaStore. */
    private fun developAndSave(upright: Bitmap, cam: FilmCamera, stamp: Boolean) {
        val uri = try {
            var out = processor().process(
                upright, cam,
                seed = Random.nextFloat(),
                leakX = Random.nextFloat(), leakY = Random.nextFloat(),
            )
            upright.recycle()
            if (stamp) PhotoStore.stampDate(out)
            if (cam.instantFrame) {
                val framed = PhotoStore.instantFrame(out)
                out.recycle()
                out = framed
            }
            PhotoStore.save(this, out, cam.id).also { out.recycle() }
        } catch (e: Exception) {
            null
        }
        main.post {
            busy = false
            if (uri == null) {
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
            } else {
                lastUri = uri
                refreshThumbnail(uri)
            }
        }
    }

    private fun importPhoto(uri: Uri) {
        if (busy) return
        busy = true
        val cam = camera
        val stamp = dateEnabled
        Toast.makeText(this, "Entwickle…", Toast.LENGTH_SHORT).show()
        processing.execute {
            val bmp = try {
                val maxSize = processor().maxSize.coerceAtMost(MAX_OUTPUT)
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    // GL needs a software bitmap; ImageDecoder applies EXIF rotation itself.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longEdge = maxOf(w, h)
                    if (longEdge > maxSize) {
                        val s = maxSize.toFloat() / longEdge
                        decoder.setTargetSize((w * s).toInt(), (h * s).toInt())
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (bmp == null) {
                main.post {
                    busy = false
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
                developAndSave(argb, cam, stamp)
            }
        }
    }

    private fun refreshThumbnail(known: Uri? = null) {
        processing.execute {
            val uri = known ?: PhotoStore.latest(this)
            val thumb = uri?.let {
                runCatching { contentResolver.loadThumbnail(it, Size(240, 240), null) }.getOrNull()
            }
            main.post {
                if (uri != null) lastUri = uri
                if (thumb != null) lastPhoto.setImageBitmap(thumb)
            }
        }
    }

    // ---- UI --------------------------------------------------------------

    private fun buildPicker() {
        val d = resources.displayMetrics.density
        for (cam in CAMERAS) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
                tag = cam.id
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    selectCamera(cam, resetDate = true)
                }
            }
            val swatch = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(cam.swatch.first, cam.swatch.second),
                ).apply { cornerRadius = 14 * d }
                layoutParams = LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt())
            }
            val label = TextView(this).apply {
                text = cam.tag
                textSize = 10f
                setTextColor(getColor(R.color.muted))
                gravity = Gravity.CENTER
                setPadding(0, (4 * d).toInt(), 0, 0)
            }
            tile.addView(swatch)
            tile.addView(label)
            picker.addView(tile)
        }
    }

    private fun selectCamera(cam: FilmCamera, resetDate: Boolean) {
        camera = cam
        renderer.camera = cam
        viewfinder.ratio = cam.ratio
        cameraName.text = cam.name.uppercase()
        prefs.edit().putString("camera", cam.id).apply()
        if (resetDate) {
            dateEnabled = cam.date
            prefs.edit().putBoolean("date", dateEnabled).apply()
            updateToggles()
        }
        val d = resources.displayMetrics.density
        for (i in 0 until picker.childCount) {
            val tile = picker.getChildAt(i) as LinearLayout
            val selected = tile.tag == cam.id
            val swatch = tile.getChildAt(0)
            (swatch.background as GradientDrawable).setStroke(
                if (selected) (3 * d).toInt() else 0, getColor(R.color.accent),
            )
            swatch.scaleX = if (selected) 1.08f else 1f
            swatch.scaleY = swatch.scaleX
            (tile.getChildAt(1) as TextView).setTextColor(
                getColor(if (selected) R.color.accent else R.color.muted),
            )
            if (selected) {
                val scroll = picker.parent as HorizontalScrollView
                scroll.post { scroll.smoothScrollTo(tile.left - (scroll.width - tile.width) / 2, 0) }
            }
        }
        glView.requestRender()
    }

    private fun updateToggles() {
        flashButton.text = when (flashMode) {
            FLASH_ON -> "FLASH ON"
            FLASH_AUTO -> "FLASH AUTO"
            else -> "FLASH OFF"
        }
        timerButton.text = if (timerSeconds == 0) "TIMER OFF" else "TIMER ${timerSeconds}S"
        dateButton.text = if (dateEnabled) "DATE ON" else "DATE OFF"
        val accent = getColor(R.color.accent)
        val text = getColor(R.color.text)
        flashButton.setTextColor(if (flashMode != FLASH_OFF) accent else text)
        timerButton.setTextColor(if (timerSeconds != 0) accent else text)
        dateButton.setTextColor(if (dateEnabled) accent else text)
        dateOverlay.text = PhotoStore.dateText()
        dateOverlay.visibility = if (dateEnabled) View.VISIBLE else View.GONE
    }

    companion object {
        private const val FLASH_OFF = 0
        private const val FLASH_AUTO = 1
        private const val FLASH_ON = 2
        /** Long-edge cap for developed photos; keeps memory and GL limits sane. */
        private const val MAX_OUTPUT = 4096
    }
}
