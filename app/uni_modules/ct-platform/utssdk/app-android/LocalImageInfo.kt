package io.ciyuantown.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.FileInputStream
import java.io.File
import java.util.concurrent.Executors
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/** Read local image bounds without Glide's nullable MIME-type callback. */
object LocalImageInfo {
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    fun readRemote(context: Context, url: String, complete: (Double, Double) -> Unit) {
        // Keep Glide's network/disk cache, but use our bounds reader instead of
        // DCloud ImageInfoTarget, which asserts that outMimeType is non-null.
        main.post {
            val manager = Glide.with(context)
            val target = object : CustomTarget<File>() {
                override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                    read(context, resource.absolutePath, complete)
                    main.post { manager.clear(this) }
                }
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    main.post { manager.clear(this) }
                    complete(0.0, 0.0)
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            }
            manager.asFile().load(url).timeout(8000).into(target)
        }
    }

    @JvmStatic
    fun read(context: Context, path: String, complete: (Double, Double) -> Unit) {
        executor.execute {
            var width = 0.0
            var height = 0.0
            try {
                val stream = when {
                    path.startsWith("/android_asset/") -> context.assets.open(path.removePrefix("/android_asset/"))
                    path.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(path))
                    path.startsWith("file://") -> FileInputStream(Uri.parse(path).path ?: "")
                    else -> FileInputStream(path)
                }
                stream?.use {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(it, null, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        width = options.outWidth.toDouble()
                        height = options.outHeight.toDouble()
                    }
                }
            } catch (_: Exception) {
                // Missing/corrupt files report failure on the main thread.
            }
            main.post { complete(width, height) }
        }
    }
}
