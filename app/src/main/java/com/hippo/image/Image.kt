/*
 * Copyright 2022 Tarsin Norbin
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.image

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
import android.graphics.ImageDecoder.DecodeException
import android.graphics.ImageDecoder.ImageInfo
import android.graphics.ImageDecoder.Source
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toDrawable
import coil.decode.FrameDelayRewritingSource
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.Native
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

class Image private constructor(
    source: Source?, drawable: Drawable? = null,
    val release: () -> Unit? = {}
) {
    private var mObtainedDrawable: Drawable?
    private var mBitmap: Bitmap? = null
    private var mCanvas: Canvas? = null

    init {
        mObtainedDrawable = null
        source?.let {
            mObtainedDrawable =
                ImageDecoder.decodeDrawable(source) { decoder: ImageDecoder, info: ImageInfo, _: Source ->
                    decoder.allocator = ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(
                        calculateSampleSize(info, 2 * screenHeight, 2 * screenWidth)
                    )
                }
        }
        if (mObtainedDrawable == null) {
            mObtainedDrawable = drawable!!
        }
        if (mObtainedDrawable is BitmapDrawable)
            release()
    }

    val animated = mObtainedDrawable is AnimatedImageDrawable
    val width = mObtainedDrawable!!.intrinsicWidth
    val height = mObtainedDrawable!!.intrinsicHeight
    val isRecycled = mObtainedDrawable == null
    var started = false

    @Synchronized
    fun recycle() {
        mObtainedDrawable ?: return
        (mObtainedDrawable as? AnimatedImageDrawable)?.stop()
        (mObtainedDrawable as? BitmapDrawable)?.bitmap?.recycle()
        mObtainedDrawable?.callback = null
        if (mObtainedDrawable is AnimatedImageDrawable)
            release()
        mObtainedDrawable = null
        mCanvas = null
        mBitmap?.recycle()
        mBitmap = null
    }

    private fun prepareBitmap() {
        if (mBitmap != null) return
        mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        mCanvas = Canvas(mBitmap!!)
    }

    private fun updateBitmap() {
        prepareBitmap()
        mCanvas!!.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        mObtainedDrawable!!.draw(mCanvas!!)
    }

    fun texImage(init: Boolean, offsetX: Int, offsetY: Int, width: Int, height: Int) {
        val bitmap: Bitmap? = if (animated) {
            updateBitmap()
            mBitmap
        } else {
            (mObtainedDrawable as BitmapDrawable?)?.bitmap
        }
        bitmap ?: return
        nativeTexImage(
            bitmap,
            init,
            offsetX,
            offsetY,
            width,
            height
        )
    }

    fun start() {
        if (!started) {
            started = true
            (mObtainedDrawable as AnimatedImageDrawable?)?.start()
        }
    }

    val delay: Int
        get() {
            return if (animated) 10 else 0
        }

    val isOpaque: Boolean
        get() {
            return false
        }

    companion object {
        fun calculateSampleSize(info: ImageInfo, targetHeight: Int, targetWeight: Int): Int {
            return min(
                info.size.width / targetWeight,
                info.size.height / targetHeight
            ).coerceAtLeast(1)
        }

        private val imageSearchMaxSize =
            EhApplication.application.resources.getDimensionPixelOffset(R.dimen.image_search_max_size)

        @JvmStatic
        val imageSearchDecoderSampleListener =
            ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                decoder.setTargetSampleSize(
                    calculateSampleSize(info, imageSearchMaxSize, imageSearchMaxSize)
                )
            }

        val screenWidth = EhApplication.application.resources.displayMetrics.widthPixels
        val screenHeight = EhApplication.application.resources.displayMetrics.heightPixels

        @Throws(DecodeException::class)
        @JvmStatic
        fun decode(stream: FileInputStream): Image {
            val buffer = stream.channel.map(
                FileChannel.MapMode.READ_ONLY, 0,
                stream.available().toLong()
            )
            val source = if (checkIsGif(buffer)) {
                rewriteSource(stream.source().buffer())
            } else {
                buffer
            }
            return Image(ImageDecoder.createSource(source))
        }

        @Throws(DecodeException::class)
        @JvmStatic
        fun decode(buffer: ByteBuffer, release: () -> Unit? = {}): Image {
            return if (checkIsGif(buffer)) {
                val rewritten = rewriteSource(Buffer().apply {
                    write(buffer)
                    release()
                })
                Image(ImageDecoder.createSource(rewritten))
            } else {
                Image(ImageDecoder.createSource(buffer)) {
                    release()
                }
            }
        }

        @JvmStatic
        fun create(bitmap: Bitmap): Image {
            return Image(null, bitmap.toDrawable(Resources.getSystem()))
        }

        private fun rewriteSource(source: BufferedSource): ByteBuffer {
            val bufferedSource = FrameDelayRewritingSource(source).buffer()
            return ByteBuffer.wrap(bufferedSource.use { it.readByteArray() })
        }

        private fun checkIsGif(buffer: ByteBuffer): Boolean {
            check(buffer.isDirect)
            return Native.isGif(buffer)
        }

        @JvmStatic
        private external fun nativeTexImage(
            bitmap: Bitmap,
            init: Boolean,
            offsetX: Int,
            offsetY: Int,
            width: Int,
            height: Int
        )
    }
}