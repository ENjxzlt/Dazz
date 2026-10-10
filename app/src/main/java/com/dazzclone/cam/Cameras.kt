package com.dazzclone.cam

/**
 * A film "camera": every numeric field maps 1:1 to a shader uniform in
 * [FilmShader]. [ratio] is the portrait width/height of the output.
 */
data class FilmCamera(
    val id: String,
    val name: String,
    val tag: String,
    val swatch: Pair<Int, Int>,
    val ratio: Float = 3f / 4f,
    val date: Boolean = false,
    val instantFrame: Boolean = false,
    val exposure: Float = 0f,      // stops
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val temp: Float = 0f,          // -1 cool .. +1 warm
    val tint: Float = 0f,          // -1 magenta .. +1 green
    val fade: Float = 0f,          // lifted blacks
    val shadow: FloatArray = floatArrayOf(0f, 0f, 0f),
    val highlight: FloatArray = floatArrayOf(0f, 0f, 0f),
    val vignette: Float = 0.3f,
    val grain: Float = 0.05f,
    val chroma: Float = 0f,        // chromatic aberration
    val blur: Float = 0f,          // softness, px at 1000px output height
    val halation: Float = 0f,      // red bloom around highlights
    val pixel: Float = 0f,         // pixelation block size, 0 = off
    val posterize: Float = 0f,     // colour levels, 0 = off
    val scan: Float = 0f,          // VHS scanlines + line jitter
    val mono: Float = 0f,
    val leak: Float = 0f,          // light-leak strength
    val leakColor: FloatArray = floatArrayOf(1f, 0.45f, 0.15f),
)

private fun c(hex: Long) = hex.toInt()

val CAMERAS = listOf(
    FilmCamera(
        "classic", "Classic", "35MM", c(0xFFF2C14E) to c(0xFFB5502B),
        date = true, temp = 0.35f, saturation = 1.1f, contrast = 1.08f, fade = 0.04f,
        shadow = floatArrayOf(0.02f, 0f, 0.04f), highlight = floatArrayOf(0.05f, 0.03f, 0f),
        vignette = 0.35f, grain = 0.07f, halation = 0.15f,
    ),
    FilmCamera(
        "fujig", "Fuji G", "35MM", c(0xFF8FBF8A) to c(0xFF2D5A4A),
        date = true, temp = -0.1f, tint = 0.2f, contrast = 1.05f, saturation = 0.95f, fade = 0.05f,
        shadow = floatArrayOf(0f, 0.03f, 0.04f), highlight = floatArrayOf(0.03f, 0.02f, -0.02f),
        vignette = 0.25f, grain = 0.06f,
    ),
    FilmCamera(
        "instant", "Instant", "SQ", c(0xFFF4EFE6) to c(0xFF7AA6C2),
        ratio = 1f, instantFrame = true,
        temp = 0.15f, contrast = 0.9f, saturation = 0.85f, fade = 0.12f,
        shadow = floatArrayOf(0f, 0.03f, 0.06f), highlight = floatArrayOf(0.06f, 0.04f, 0f),
        blur = 0.8f, vignette = 0.4f, grain = 0.05f, leak = 0.25f,
    ),
    FilmCamera(
        "ccd", "CCD", "DIGI", c(0xFF9FB7D9) to c(0xFF33415C),
        date = true, temp = -0.25f, contrast = 1.15f, saturation = 1.15f, exposure = 0.15f,
        chroma = 0.15f, grain = 0.03f, vignette = 0.1f,
    ),
    FilmCamera(
        "noir", "Noir", "B&W", c(0xFFE6E6E6) to c(0xFF1A1A1A),
        mono = 1f, contrast = 1.35f, grain = 0.14f, vignette = 0.5f, fade = 0.03f,
    ),
    FilmCamera(
        "vhs", "VHS", "TAPE", c(0xFF5AD1E6) to c(0xFFD14A8B),
        date = true, scan = 1f, chroma = 0.6f, blur = 1.2f, saturation = 1.25f, temp = 0.05f,
        fade = 0.08f, grain = 0.08f,
    ),
    FilmCamera(
        "slide", "Slide", "E6", c(0xFF2F7FD1) to c(0xFFE0A21B),
        saturation = 1.35f, contrast = 1.2f, temp = 0.05f, exposure = -0.1f,
        vignette = 0.45f, grain = 0.04f,
    ),
    FilmCamera(
        "lomo", "Lomo", "SQ", c(0xFFE84A5F) to c(0xFF2A363B),
        ratio = 1f, saturation = 1.4f, contrast = 1.3f, vignette = 0.9f, chroma = 0.3f,
        leak = 0.35f, tint = -0.1f,
        highlight = floatArrayOf(0.05f, 0.05f, -0.05f), shadow = floatArrayOf(0f, 0f, 0.08f),
        grain = 0.06f,
    ),
    FilmCamera(
        "dusk", "Dusk", "35MM", c(0xFFFF9A8B) to c(0xFF6A3093),
        temp = 0.6f, tint = -0.15f, fade = 0.1f, highlight = floatArrayOf(0.08f, 0.02f, 0.06f),
        leak = 0.45f, leakColor = floatArrayOf(1f, 0.3f, 0.5f), halation = 0.35f, grain = 0.06f,
    ),
    FilmCamera(
        "pixel", "Pixel", "8BIT", c(0xFF7BD389) to c(0xFF3A2E39),
        pixel = 6f, posterize = 6f, saturation = 1.2f, contrast = 1.1f, grain = 0f, vignette = 0f,
    ),
)
