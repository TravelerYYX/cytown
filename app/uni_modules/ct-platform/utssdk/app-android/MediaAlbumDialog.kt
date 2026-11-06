package io.ciyuantown.media

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.DialogFragment
import android.content.DialogInterface
import android.util.Log
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import android.window.OnBackInvokedDispatcher
import com.bumptech.glide.Glide
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.*

/** Fullscreen native album hosted by the existing activity; no manifest activity registration. */
class MediaAlbumDialog : DialogFragment() {
    companion object {
        private var pending: ((String) -> Unit)? = null
        @JvmStatic fun open(activity: Activity, spec: String, complete: (String) -> Unit) {
            if (pending != null) { complete("{\"error\":\"相册已打开\"}"); return }
            pending = complete
            try {
                val album = MediaAlbumDialog()
                album.arguments = Bundle().apply { putString("spec", spec) }
                album.show(activity.fragmentManager, "ct-media-album")
            } catch (error: Exception) {
                Log.e("CTMediaAlbum", "Cannot attach album dialog", error)
                pending = null; complete("{\"error\":\"相册暂时无法打开，请返回页面后重试\"}")
            }
        }
    }
    private data class Media(val uri: Uri, val name: String, val bucket: String, val album: String, val size: Long, val width: Int, val height: Int, val duration: Long, val date: Long)
    private val host: Activity get() = activity ?: throw IllegalStateException("Album host unavailable")
    private var destroyed = false
    private val isFinishing: Boolean get() = completed || !isAdded
    private val isDestroyed: Boolean get() = destroyed
    private val applicationContext get() = host.applicationContext
    private val contentResolver get() = host.contentResolver
    private val cacheDir get() = host.cacheDir
    private val packageName get() = host.packageName
    private fun runOnUiThread(action: () -> Unit) { activity?.runOnUiThread(action) }
    private val worker = Executors.newSingleThreadExecutor()
    private val selected = linkedMapOf<String, Media>()
    private val all = arrayListOf<Media>()
    private var shown: List<Media> = emptyList()
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var title: TextView
    private lateinit var send: TextView
    private lateinit var preview: TextView
    private lateinit var originalButton: TextView
    private lateinit var grid: GridView
    private lateinit var adapter: AlbumAdapter
    private var video = false
    private var original = false
    private var limit = 1
    private var crop = 0
    private var cropQuality = 85
    private var camera = false
    private var confirmText = "发送"
    private var bucket = ""
    private var albumTitle = ""
    private var busy = false
    private var loading = false
    private var completed = false
    private var permissionRequested = false
    private var overlay: FrameLayout? = null
    private var player: VideoView? = null
    private var albumPopup: PopupWindow? = null
    private var cropView: CropView? = null
    private var previewItems: List<Media> = emptyList()
    private var previewIndex = 0
    private val blue = Color.rgb(0, 145, 224)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun round(color: Int, radius: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 16f) = TextView(host).apply { text = value; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false }
    private fun button(value: String, size: Float = 16f, click: () -> Unit) = text(value, size).apply { isClickable = true; isFocusable = true; setOnClickListener { if (!busy) click() } }
    private fun toast(value: String) { Toast.makeText(host, value, Toast.LENGTH_SHORT).show() }
    override fun onCreateDialog(state: Bundle?): Dialog {
        val modal = object : Dialog(host, android.R.style.Theme_Material_NoActionBar) {
            @Deprecated("Dialog back compatibility") override fun onBackPressed() { closeOrCancel() }
        }
        modal.setCanceledOnTouchOutside(false)
        if (pending == null) { modal.setOnShowListener { dismissAllowingStateLoss() }; return modal }
        val spec = JSONObject(arguments?.getString("spec") ?: "{}")
        val window = modal.window!!
        video = spec.optString("kind") == "video"; original = spec.optBoolean("original", true)
        limit = spec.optInt("count", 1).coerceAtLeast(1); crop = spec.optInt("crop", 0).coerceIn(0, 2048)
        cropQuality = spec.optInt("quality", 85).coerceIn(1, 100); camera = spec.optBoolean("camera")
        confirmText = spec.optString("confirmText", "发送")
        albumTitle = if (video) "本地视频" else "最近照片"
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
        root = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        if (Build.VERSION.SDK_INT >= 35) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        modal.setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) modal.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { closeOrCancel() }
        val header = FrameLayout(host)
        header.addView(button("×", 38f) { closeOrCancel() }, FrameLayout.LayoutParams(dp(52), -1, Gravity.START))
        title = button("$albumTitle ﹀", 19f) { showAlbums() }
        header.addView(title, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(46)))
        content = FrameLayout(host)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        grid = GridView(host).apply { numColumns = 4; horizontalSpacing = dp(1); verticalSpacing = dp(1); stretchMode = GridView.STRETCH_COLUMN_WIDTH; setPadding(0, 0, 0, 0); clipToPadding = false; isVerticalScrollBarEnabled = false }
        adapter = AlbumAdapter(); grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> if (!busy && position in shown.indices) showPreview(shown, position) }
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val footer = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), dp(10)) }
        preview = button("预览") { if (selected.isNotEmpty()) showPreview(selected.values.toList(), 0) }
        footer.addView(preview, LinearLayout.LayoutParams(dp(45), dp(48)))
        originalButton = button("") { original = !original; updateFooter() }
        footer.addView(originalButton, LinearLayout.LayoutParams(dp(78), dp(48)))
        footer.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        send = button(confirmText) { confirm() }.apply { background = round(blue, 24) }
        footer.addView(send, LinearLayout.LayoutParams(dp(76), dp(40)))
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(78)))
        updateFooter()
        if (state != null) {
            original = state.getBoolean("original", original); bucket = state.getString("bucket", "")
            albumTitle = state.getString("albumTitle", albumTitle); title.text = "$albumTitle ﹀"
            restoredIds = state.getStringArrayList("selection") ?: arrayListOf()
        }
        permissionRequested = state?.getBoolean("requested") ?: false
        root.post {
            if (!destroyed && isAdded) {
                if (hasAccess()) loadMedia() else if (!permissionRequested) requestAccess() else showEmpty("请允许访问相册后选择")
            }
        }
        return modal
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    private var restoredIds = arrayListOf<String>()
    private fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        val kind = if (video) "android.permission.READ_MEDIA_VIDEO" else "android.permission.READ_MEDIA_IMAGES"
        if (Build.VERSION.SDK_INT >= 34) arrayOf(kind, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") else arrayOf(kind)
    } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun hasAccess() = permissions().any { host.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun requestAccess() { permissionRequested = true; requestPermissions(permissions(), 8421) }
    override fun onRequestPermissionsResult(code: Int, names: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, names, results)
        if (code == 8421) { if (hasAccess()) loadMedia() else showEmpty("请允许访问相册后选择") }
    }
    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && permissionRequested && !loading && !busy) {
            if (hasAccess()) loadMedia() else { all.clear(); selected.clear(); filterMedia(); updateFooter() }
        }
    }
    private fun loadMedia() {
        if (loading) return
        loading = true
        worker.execute {
            val rows = arrayListOf<Media>(); var failed = false
            try {
                val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val columns = arrayOf("_id", "_display_name", "bucket_id", "bucket_display_name", "_size", "width", "height", "date_added") + if (video) arrayOf("duration") else emptyArray()
                contentResolver.query(uri, columns, null, null, "date_added DESC, _id DESC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(Media(ContentUris.withAppendedId(uri, cursor.getLong(0)), cursor.getString(1) ?: "media", cursor.getString(2) ?: "", cursor.getString(3) ?: "其他", cursor.getLong(4), cursor.getInt(5), cursor.getInt(6), if (video) cursor.getLong(8) else 0L, cursor.getLong(7)))
                    }
                }
            } catch (_: Exception) { failed = true }
            runOnUiThread {
                loading = false
                if (!isFinishing && !isDestroyed) {
                    all.clear(); all.addAll(rows)
                    selected.keys.retainAll(rows.map { it.uri.toString() }.toSet())
                    for (id in restoredIds) rows.find { it.uri.toString() == id }?.let { selected[id] = it }
                    restoredIds.clear(); filterMedia(); updateFooter()
                    if (failed) showEmpty("相册读取失败，点击重试")
                }
            }
        }
    }
    private var empty: TextView? = null
    private fun showEmpty(message: String) {
        empty?.let { content.removeView(it) }
        empty = button(message + "\n\n" + if (hasAccess()) "重新加载" else "授权访问相册", 15f) {
            if (hasAccess()) loadMedia() else if (Build.VERSION.SDK_INT >= 34) requestAccess() else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.apply { setTextColor(Color.LTGRAY) }
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
    }
    private fun filterMedia() {
        empty?.let { content.removeView(it) }; empty = null
        shown = if (bucket.isEmpty()) all.toList() else all.filter { it.bucket == bucket }
        adapter.notifyDataSetChanged()
        if (shown.isEmpty()) showEmpty(if (hasAccess()) "暂无可用的${if (video) "视频" else "照片"}" else "请允许访问相册后选择")
    }
    private fun showAlbums() {
        if (overlay != null) return
        val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(24, 24, 24)) }
        val scroll = ScrollView(host).apply { addView(list) }
        albumPopup = PopupWindow(scroll, -1, min(dp(420), resources.displayMetrics.heightPixels / 2), true).apply { setBackgroundDrawable(round(Color.rgb(24, 24, 24))); isOutsideTouchable = true; elevation = dp(12).toFloat() }
        fun row(label: String, click: () -> Unit) { list.addView(button(label, 16f) { albumPopup?.dismiss(); click() }, LinearLayout.LayoutParams(-1, dp(56))) }
        row("${if (video) "本地视频" else "最近照片"}  (${all.size})") { bucket = ""; albumTitle = if (video) "本地视频" else "最近照片"; title.text = "$albumTitle ﹀"; filterMedia(); grid.setSelection(0) }
        all.groupBy { it.bucket }.forEach { (id, items) -> row("${items.first().album}  (${items.size})") { bucket = id; albumTitle = items.first().album; title.text = "$albumTitle ﹀"; filterMedia(); grid.setSelection(0) } }
        if (camera) row(if (video) "拍摄视频" else "拍照") { finishResult(JSONObject().put("action", "camera").toString()) }
        if (Build.VERSION.SDK_INT >= 34 && host.checkSelfPermission(permissions()[0]) != PackageManager.PERMISSION_GRANTED) row("选择更多可访问的${if (video) "视频" else "照片"}") { requestAccess() }
        albumPopup?.showAsDropDown(root.getChildAt(0))
    }
    private fun toggle(media: Media) {
        val id = media.uri.toString()
        if (selected.containsKey(id)) selected.remove(id)
        else {
            if (selected.size >= limit) { toast("最多选择${limit}个${if (video) "视频" else "照片"}"); return }
            selected[id] = media
        }
        adapter.notifyDataSetChanged(); updateFooter()
    }
    private fun updateFooter() {
        preview.alpha = if (selected.isEmpty()) .35f else 1f
        send.alpha = if (selected.isEmpty()) .48f else 1f
        send.text = if (busy) "处理中" else if (selected.isEmpty()) confirmText else "$confirmText(${selected.size})"
        originalButton.text = if (original) "◉ 原图" else "◯ 原图"
        originalButton.visibility = if (crop > 0) View.INVISIBLE else View.VISIBLE
    }
    private inner class AlbumAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val cell = recycled as? FrameLayout ?: FrameLayout(host).apply {
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                addView(text("", 12f).apply { setPadding(dp(6), dp(2), dp(6), dp(2)); background = round(0x77000000, 12) }, FrameLayout.LayoutParams(-2, dp(24), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(6)) })
                addView(SelectionView(), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END))
            }
            val edge = ((grid.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - dp(3)) / 4
            // Preserve GridView's recycling metadata, including forceAdd for its measured first cell.
            val params = cell.layoutParams as? AbsListView.LayoutParams ?: AbsListView.LayoutParams(-1, edge)
            params.height = edge
            cell.layoutParams = params
            val media = shown[position]; val image = cell.getChildAt(0) as ImageView
            Glide.with(host).load(media.uri).override(edge, edge).centerCrop().into(image)
            (cell.getChildAt(1) as TextView).apply { visibility = if (video) View.VISIBLE else View.GONE; text = "▶ " + duration(media.duration) }
            (cell.getChildAt(2) as SelectionView).apply {
                isFocusable = false
                order = selected.keys.indexOf(media.uri.toString()) + 1; invalidate()
                contentDescription = "${if (order > 0) "取消选择" else "选择"}第${position + 1}项"
                setOnClickListener { if (!busy) toggle(media) }
            }
            cell.contentDescription = "预览${media.name}"
            cell.isClickable = false; cell.isFocusable = false
            return cell
        }
    }
    private fun duration(ms: Long): String { val seconds = ms / 1000; return "%02d:%02d".format(seconds / 60, seconds % 60) }
    private inner class SelectionView : View(host) {
        var order = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isClickable = true; isFocusable = true }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info); info.isCheckable = true; info.isChecked = order > 0
        }
        override fun onDraw(canvas: Canvas) {
            val x = width / 2f; val y = height / 2f; val r = dp(10).toFloat()
            paint.style = Paint.Style.FILL; paint.color = if (order > 0) blue else 0x66000000; canvas.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = Color.WHITE; canvas.drawCircle(x, y, r, paint)
            if (order > 0) { paint.style = Paint.Style.FILL; paint.textSize = dp(12).toFloat(); paint.textAlign = Paint.Align.CENTER; canvas.drawText(order.toString(), x, y - (paint.ascent() + paint.descent()) / 2, paint) }
        }
    }
    private fun showPreview(items: List<Media>, index: Int) {
        closePreview(); previewItems = items; previewIndex = index
        val layer = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }; overlay = layer
        content.addView(layer, FrameLayout.LayoutParams(-1, -1)); renderPreview()
    }
    private fun renderPreview() {
        val layer = overlay ?: return; player?.stopPlayback(); player = null; layer.removeAllViews()
        val media = previewItems[previewIndex]
        if (video) {
            val view = VideoView(host); player = view
            layer.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)); view.setVideoURI(media.uri)
            val controller = MediaController(host); view.setMediaController(controller); controller.setAnchorView(view)
            view.setOnPreparedListener { it.isLooping = false; view.start() }
            view.setOnErrorListener { _, _, _ -> toast("视频无法播放，请重新选择"); true }
        } else {
            val image = ImageView(host).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            layer.addView(image, FrameLayout.LayoutParams(-1, -1)); Glide.with(host).load(media.uri).into(image)
        }
        layer.addView(button("‹ 返回相册", 16f) { closePreview() }.apply { background = round(0x88000000.toInt(), 16) }, FrameLayout.LayoutParams(dp(104), dp(44), Gravity.TOP or Gravity.START))
        val choose = SelectionView().apply { order = selected.keys.indexOf(media.uri.toString()) + 1; contentDescription = "勾选当前媒体"; setOnClickListener { toggle(media); order = selected.keys.indexOf(media.uri.toString()) + 1; invalidate() } }
        layer.addView(choose, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        if (previewItems.size > 1) {
            val nav = LinearLayout(host).apply { gravity = Gravity.CENTER; setBackgroundColor(0x88000000.toInt()) }
            nav.addView(button("‹", 30f) { previewIndex = (previewIndex - 1 + previewItems.size) % previewItems.size; renderPreview() }, LinearLayout.LayoutParams(dp(64), dp(48)))
            nav.addView(text("${previewIndex + 1} / ${previewItems.size}"), LinearLayout.LayoutParams(dp(100), dp(48)))
            nav.addView(button("›", 30f) { previewIndex = (previewIndex + 1) % previewItems.size; renderPreview() }, LinearLayout.LayoutParams(dp(64), dp(48)))
            layer.addView(nav, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        }
    }
    private fun closePreview() { player?.stopPlayback(); player = null; overlay?.let { content.removeView(it) }; overlay = null; cropView?.release(); cropView = null }
    private fun confirm() {
        if (selected.isEmpty() || busy) return
        if (crop > 0 && cropView == null) { startCrop(selected.values.first()); return }
        val items = selected.values.toList(); val cropBitmap = cropView?.export(crop)
        if (crop > 0 && cropBitmap == null) { toast("图片仍在加载，请稍后"); return }
        busy = true; player?.pause(); updateFooter()
        val useOriginal = original
        worker.execute {
            val created = arrayListOf<File>()
            try {
                val dir = File(cacheDir, "ct-album").apply { mkdirs() }
                val files = JSONArray()
                for (media in items) {
                    val ext = media.name.substringAfterLast('.', if (video) "mp4" else "jpg").filter { it.isLetterOrDigit() }.take(8).ifEmpty { if (video) "mp4" else "jpg" }
                    val file = File(dir, UUID.randomUUID().toString() + "." + if (cropBitmap != null) "jpg" else ext); created.add(file)
                    var width = media.width; var height = media.height
                    if (cropBitmap != null) {
                        file.outputStream().use { if (!cropBitmap.compress(Bitmap.CompressFormat.JPEG, cropQuality, it)) throw Exception("encode") }; width = crop; height = crop
                    } else if (!video && !useOriginal && !ext.equals("gif", true)) {
                        val bitmap = Glide.with(applicationContext).asBitmap().load(media.uri).submit(2048, 2048)
                        try {
                            val decoded = bitmap.get(); width = decoded.width; height = decoded.height
                            file.outputStream().use { if (!decoded.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw Exception("encode") }
                        } finally { Glide.with(applicationContext).clear(bitmap) }
                        // Keep the extension consistent with the encoded content.
                        val jpeg = File(dir, file.nameWithoutExtension + ".jpg")
                        if (file != jpeg) { if (!file.renameTo(jpeg)) throw Exception("rename"); created[created.lastIndex] = jpeg }
                    } else contentResolver.openInputStream(media.uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: throw Exception("read")
                    val output = created.last()
                    files.put(JSONObject().put("path", output.absolutePath).put("size", output.length()).put("width", width).put("height", height).put("duration", media.duration / 1000.0))
                }
                val result = JSONObject().put("files", files).put("original", useOriginal).toString()
                runOnUiThread { if (!isFinishing && !isDestroyed) finishResult(result) else created.forEach { it.delete() } }
            } catch (_: Exception) {
                created.forEach { it.delete() }
                runOnUiThread { if (!isFinishing && !isDestroyed) { busy = false; updateFooter(); toast("文件读取失败，请检查相册权限后重试") } }
            } finally { cropBitmap?.recycle() }
        }
    }
    private fun startCrop(media: Media) {
        closePreview()
        val layer = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }; overlay = layer
        val view = CropView(); cropView = view; layer.addView(view, FrameLayout.LayoutParams(-1, -1))
        layer.addView(button("‹ 返回相册") { closePreview(); updateFooter() }, FrameLayout.LayoutParams(dp(110), dp(48), Gravity.TOP or Gravity.START))
        layer.addView(text("拖动或双指缩放，调整裁剪区域", 14f), FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        content.addView(layer, FrameLayout.LayoutParams(-1, -1)); send.text = "确认"
        worker.execute {
            val request = Glide.with(applicationContext).asBitmap().load(media.uri).submit(2048, 2048)
            val bitmap = try { request.get().copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null } finally { Glide.with(applicationContext).clear(request) }
            runOnUiThread { if (!isDestroyed && cropView === view) { if (bitmap != null) view.setBitmap(bitmap) else { closePreview(); toast("图片无法裁剪，请重新选择") } } else bitmap?.recycle() }
        }
    }
    private inner class CropView : View(host) {
        private var bitmap: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var zoom = 1f; private var offsetX = 0f; private var offsetY = 0f; private var lastX = 0f; private var lastY = 0f
        private val scaler = ScaleGestureDetector(host, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { zoom = (zoom * detector.scaleFactor).coerceIn(1f, 8f); clamp(); invalidate(); return true }
        })
        fun setBitmap(value: Bitmap) { bitmap = value; invalidate() }
        fun release() { bitmap?.recycle(); bitmap = null }
        private fun side() = min(width, height - dp(120)).coerceAtLeast(1).toFloat()
        private fun scale(): Float { val b = bitmap ?: return 1f; return max(side() / b.width, side() / b.height) * zoom }
        private fun clamp() { val b = bitmap ?: return; val s = scale(); val x = max(0f, (b.width * s - side()) / 2); val y = max(0f, (b.height * s - side()) / 2); offsetX = offsetX.coerceIn(-x, x); offsetY = offsetY.coerceIn(-y, y) }
        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return; clamp(); val s = scale(); val left = (width - b.width * s) / 2 + offsetX; val top = (height - b.height * s) / 2 + offsetY
            canvas.drawBitmap(b, null, RectF(left, top, left + b.width * s, top + b.height * s), paint)
            val edge = side(); val x = (width - edge) / 2; val y = (height - edge) / 2
            paint.color = 0xaa000000.toInt(); paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), y, paint); canvas.drawRect(0f, y + edge, width.toFloat(), height.toFloat(), paint)
            canvas.drawRect(0f, y, x, y + edge, paint); canvas.drawRect(x + edge, y, width.toFloat(), y + edge, paint)
            paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); canvas.drawRect(x, y, x + edge, y + edge, paint); paint.style = Paint.Style.FILL
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaler.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
                MotionEvent.ACTION_MOVE -> { if (!scaler.isInProgress && event.pointerCount == 1) { offsetX += event.x - lastX; offsetY += event.y - lastY; clamp(); invalidate() }; lastX = event.x; lastY = event.y }
                MotionEvent.ACTION_POINTER_UP -> { val next = if (event.actionIndex == 0) 1 else 0; lastX = event.getX(next); lastY = event.getY(next) }
            }
            return true
        }
        fun export(edge: Int): Bitmap? {
            val b = bitmap ?: return null; clamp(); val s = scale(); val factor = edge / side()
            val left = ((width - b.width * s) / 2 + offsetX - (width - side()) / 2) * factor
            val top = ((height - b.height * s) / 2 + offsetY - (height - side()) / 2) * factor
            return Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(b, null, RectF(left, top, left + b.width * s * factor, top + b.height * s * factor), paint) }
        }
    }
    private fun closeOrCancel() { if (busy) return; if (overlay != null) { closePreview(); updateFooter() } else finishResult("{\"cancelled\":true}") }
    private fun finishResult(value: String) { if (completed) return; completed = true; val callback = pending; pending = null; dismissAllowingStateLoss(); callback?.invoke(value) }
    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("original", original); out.putBoolean("requested", permissionRequested); out.putString("bucket", bucket); out.putString("albumTitle", albumTitle)
        out.putStringArrayList("selection", ArrayList(selected.keys)); super.onSaveInstanceState(out)
    }
    override fun onPause() { player?.pause(); super.onPause() }
    override fun onCancel(dialog: DialogInterface) { super.onCancel(dialog); if (!completed) finishResult("{\"cancelled\":true}") }
    override fun onDestroy() {
        destroyed = true
        albumPopup?.dismiss(); if (::content.isInitialized) closePreview(); worker.shutdown()
        if (activity?.isChangingConfigurations != true && !completed) { val callback = pending; pending = null; callback?.invoke("{\"cancelled\":true}") }
        super.onDestroy()
    }
}
