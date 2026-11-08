package com.liquid.lfmomni.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer

/** 画像の読み込み・縮小・RGB 変換 (libmtmd は RGB 3byte/px を要求する) */
object ImageUtils {

    fun loadScaled(context: Context, uri: Uri, maxSize: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("画像を開けません")
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("画像として読み込めません")
        }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IOException("画像のデコードに失敗しました")

        val rotated = applyExif(context, uri, decoded)
        return scaleToFit(rotated, maxSize)
    }

    private fun applyExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@use ExifInterface.ORIENTATION_NORMAL
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun scaleToFit(bitmap: Bitmap, maxSize: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSize) return bitmap
        val scale = maxSize.toFloat() / longest.toFloat()
        val w = (bitmap.width * scale).toInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** ARGB_8888 の Bitmap を RGBRGB... のバイト列にする */
    fun toRgb(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        var o = 0
        for (p in pixels) {
            out[o++] = ((p shr 16) and 0xFF).toByte()
            out[o++] = ((p shr 8) and 0xFF).toByte()
            out[o++] = (p and 0xFF).toByte()
        }
        return out
    }

    /** 進捗表示などで使う簡易サムネイル (最大 256px) */
    fun loadThumbnail(context: Context, uri: Uri, maxSize: Int = 256): Bitmap? =
        runCatching { loadScaled(context, uri, maxSize) }.getOrNull()

    fun rgbToBitmap(rgb: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val buffer = ByteBuffer.wrap(rgb)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
