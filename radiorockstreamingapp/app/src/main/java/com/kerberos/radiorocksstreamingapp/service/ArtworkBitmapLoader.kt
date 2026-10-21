package com.kerberos.radiorocksstreamingapp.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.kerberos.radiorocksstreamingapp.R
import com.kerberos.radiorocksstreamingapp.data.TrackMetadataStore
import com.kerberos.radiorocksstreamingapp.network.AlertLevel
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class ArtworkBitmapLoader(private val context: Context) : BitmapLoader {

    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/", ignoreCase = true)
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return executor.submit(object : java.util.concurrent.Callable<Bitmap> {
            override fun call(): Bitmap {
                val raw = BitmapFactory.decodeByteArray(data, 0, data.size)
                    ?: decodeFallbackLogo()
                return applyAlertBorder(raw)
            }
        })
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return executor.submit(object : java.util.concurrent.Callable<Bitmap> {
            override fun call(): Bitmap {
                val input: InputStream? = try {
                    when (uri.scheme) {
                        "http", "https" -> {
                            val conn = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 6000
                                readTimeout = 6000
                                requestMethod = "GET"
                                setRequestProperty("User-Agent", "RadioRocksApp/1.0")
                            }
                            if (conn.responseCode != 200) {
                                conn.disconnect()
                                null
                            } else conn.inputStream
                        }
                        else -> context.contentResolver.openInputStream(uri)
                    }
                } catch (_: Exception) {
                    null
                }

                val bitmap = input?.use { BitmapFactory.decodeStream(it) } ?: decodeFallbackLogo()
                return applyAlertBorder(bitmap)
            }
        })
    }

    private fun decodeFallbackLogo(): Bitmap {
        return try {
            BitmapFactory.decodeResource(context.resources, R.drawable.roks_logo)
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun applyAlertBorder(bitmap: Bitmap): Bitmap {
        val level = TrackMetadataStore.alertLevel.value
        if (level == AlertLevel.NONE) return bitmap

        val width = bitmap.width
        val height = bitmap.height
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val stroke = (width * 0.08f).coerceAtLeast(16f)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = if (level == AlertLevel.RED) Color.RED else Color.YELLOW
            isAntiAlias = true
        }

        val halfStroke = stroke / 2f
        val rect = RectF(halfStroke, halfStroke, width - halfStroke, height - halfStroke)
        canvas.drawRect(rect, paint)

        return mutableBitmap
    }
}
