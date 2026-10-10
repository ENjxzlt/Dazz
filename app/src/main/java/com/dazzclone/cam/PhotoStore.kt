package com.dazzclone.cam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoStore {

    /** Dazz-style stamp, e.g. `'26 10 10`. */
    fun dateText(date: Date = Date()): String =
        "'" + SimpleDateFormat("yy MM dd", Locale.US).format(date)

    /** Burns an orange, glowing LED-style date into the bottom-right corner. */
    fun stampDate(bmp: Bitmap) {
        val canvas = Canvas(bmp)
        val size = minOf(bmp.width, bmp.height) * 0.045f
        val text = dateText()
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = size
            letterSpacing = 0.08f
        }
        val x = bmp.width - base.measureText(text) - size * 1.2f
        val y = bmp.height - size * 1.2f
        val glow = Paint(base).apply {
            color = Color.argb(170, 255, 80, 0)
            maskFilter = BlurMaskFilter(size * 0.35f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawText(text, x, y, glow)
        canvas.drawText(text, x, y, Paint(base).apply { color = Color.rgb(255, 150, 40) })
    }

    /** Instant-film border: thin sides and top, thick bottom. */
    fun instantFrame(bmp: Bitmap): Bitmap {
        val side = (bmp.width * 0.06f).toInt()
        val bottom = (bmp.width * 0.24f).toInt()
        val out = Bitmap.createBitmap(bmp.width + side * 2, bmp.height + side + bottom, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(244, 241, 234))
        canvas.drawBitmap(bmp, side.toFloat(), side.toFloat(), null)
        return out
    }

    /** Writes a JPEG to Pictures/DazzClone via MediaStore (API 29+, no permission needed). */
    fun save(context: Context, bmp: Bitmap, cameraId: String): Uri? {
        val name = "DAZZ_${cameraId}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DazzClone")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: throw IllegalStateException("No output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Most recent photo this app saved, for the gallery thumbnail. */
    fun latest(context: Context): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%DazzClone%")
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) {
                return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            }
        }
        return null
    }
}
