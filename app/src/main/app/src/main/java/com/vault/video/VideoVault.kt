package com.vault.video

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.MediaController
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.util.concurrent.Executors

object Vault {
    private val WATCH_DIRS = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
        "WhatsApp/Media/WhatsApp Video",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video",
        "WhatsApp Business/Media/WhatsApp Business Video"
    )
    private val EXT = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi")

    fun dir(ctx: Context): File = File(ctx.filesDir, "vault").also { it.mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun sourceFolders(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return WATCH_DIRS.map { File(root, it) }.filter { it.isDirectory }
    }

    fun isVideo(f: File) = f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in EXT

    fun capture(ctx: Context, src: File): Boolean {
        var dest: File? = null
        return try {
            val mod = src.lastModified()
            val len = src.length()
            var d = File(dir(ctx), src.name)
            if (d.exists()) d = File(dir(ctx), "${System.currentTimeMillis()}_${src.name}")
            dest = d
            src.copyTo(d)
            if (d.length() != len) {
                d.delete()
                return false
            }
            val path = src.absolutePath
            if (!src.delete()) {
                d.delete()
                return false
            }
            d.setLastModified(mod)
            MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
            true
        } catch (e: Exception) {
            dest?.delete()
            false
        }
    }
}

class WatcherService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (worker == null) {
            running = true
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("vault", "Video Vault", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "vault")
            .setContentTitle("Video Vault is active")
            .setContentText("New WhatsApp videos are moved into the vault")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun loop() {
        val lastSize = HashMap<String, Long>()
        val failed = HashSet<String>()
        while (running) {
            try {
                if (Environment.isExternalStorageManager()) {
                    for (folder in Vault.sourceFolders()) {
                        folder.listFiles()?.forEach { f ->
                            if (!Vault.isVideo(f) || f.absolutePath in failed) return@forEach
                            val key = f.absolutePath
                            val size = f.length()
                            val stable = lastSize[key] == size &&
                                System.currentTimeMillis() - f.lastModified() > 3000
                            if (stable && size > 0) {
                                if (!Vault.capture(this, f)) failed.add(key)
                                lastSize.remove(key)
                            } else {
                                lastSize[key] = size
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val on = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE).getBoolean("on", true)
        if (on && Environment.isExternalStorageManager()) {
            try {
                ctx.startForegroundService(Intent(ctx, WatcherService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = LruCache<String, Bitmap>(80)
    private var files: List<File> = emptyList()
    private val green = Color.parseColor("#075E54")

    private lateinit var summary: TextView
    private lateinit var banner: LinearLayout
    private lateinit var toggle: Switch
    private lateinit var empty: TextView
    private lateinit var listView: ListView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("vault", MODE_PRIVATE)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun sizeText(b: Long) =
        if (b >= (1L shl 30)) "%.1f GB".format(b / 1073741824.0) else "${b / 1048576} MB"

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(p: Int) = files[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val row = cv as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val iv = ImageView(context).apply {
                    id = 1
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.parseColor("#D7EEE7"))
                }
                addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)))
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }
                col.addView(TextView(context).apply {
                    id = 2; textSize = 14f; setTextColor(Color.BLACK); maxLines = 2
                })
                col.addView(TextView(context).apply {
                    id = 3; textSize = 12f; setTextColor(Color.GRAY)
                })
                addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val f = files[pos]
            row.findViewById<TextView>(2).text = f.name
            row.findViewById<TextView>(3).text = sizeText(f.length())
            loadThumb(f, row.findViewById(1))
            return row
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = green

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3F7F6"))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(green)
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }
        top.addView(TextView(this).apply {
            text = "Video Vault"; textSize = 22f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        summary = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#CCE9E2")) }
        top.addView(summary)
        root.addView(top)

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF3CD"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        banner.addView(TextView(this).apply {
            text = "Video Vault needs \"All files access\" to move videos out of the WhatsApp folder."
            setTextColor(Color.parseColor("#5C4B00")); textSize = 13f
        })
        banner.addView(Button(this).apply {
            text = "Allow access"
            setOnClickListener { openAccessSettings() }
        })
        root.addView(banner)

        toggle = Switch(this).apply {
            text = "Auto-capture new WhatsApp videos"
            setPadding(dp(16), dp(12), dp(16), dp(4))
            isChecked = prefs().getBoolean("on", true)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean("on", on).apply()
                if (on) startCapture() else stopService(Intent(this@MainActivity, WatcherService::class.java))
            }
        }
        root.addView(toggle)

        root.addView(Button(this).apply {
            text = "Move existing WhatsApp videos into vault"
            setOnClickListener { importExisting() }
        })

        empty = TextView(this).apply {
            text = "Your vault is empty.\nNew WhatsApp videos will appear here automatically."
            gravity = Gravity.CENTER; setTextColor(Color.GRAY); setPadding(dp(30), dp(40), dp(30), 0)
        }
        root.addView(empty)

        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(
                    Intent(this@MainActivity, PlayerActivity::class.java)
                        .putExtra("path", files[pos].absolutePath)
                )
            }
            setOnItemLongClickListener { _, _, pos, _ -> showMenu(files[pos]); true }
        }
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val ok = Environment.isExternalStorageManager()
        banner.visibility = if (ok) View.GONE else View.VISIBLE
        if (ok && prefs().getBoolean("on", true)) startCapture()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun openAccessSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun startCapture() {
        if (Environment.isExternalStorageManager()) {
            startForegroundService(Intent(this, WatcherService::class.java))
        }
    }

    private fun refresh() {
        val now = Vault.list(this)
        if (now.map { it.name } != files.map { it.name }) {
            files = now
            adapter.notifyDataSetChanged()
        }
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        val total = files.sumOf { it.length() }
        summary.text = "${files.size} video(s) · ${sizeText(total)} · hidden from Gallery"
    }

    private fun loadThumb(f: File, iv: ImageView) {
        val key = f.absolutePath
        iv.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageResource(android.R.drawable.ic_media_play)
        pool.execute {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(key)
                val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val small = Bitmap.createScaledBitmap(
                        frame, 240, maxOf(1, 240 * frame.height / frame.width), true
                    )
                    cache.put(key, small)
                    runOnUiThread { if (iv.tag == key) iv.setImageBitmap(small) }
                }
            } catch (e: Exception) {
            } finally {
                r.release()
            }
        }
    }

    private fun showMenu(f: File) {
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(arrayOf("Save back to Gallery", "Delete permanently")) { _, which ->
                if (which == 0) exportToGallery(f) else {
                    AlertDialog.Builder(this).setTitle("Delete this video?")
                        .setMessage("This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ -> f.delete(); refresh() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun exportToGallery(f: File) {
        Thread {
            try {
                val mime = when (f.extension.lowercase()) {
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    "3gp" -> "video/3gpp"
                    else -> "video/mp4"
                }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                    put(MediaStore.Video.Media.MIME_TYPE, mime)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoVault")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
                f.delete()
                runOnUiThread { toast("Saved to Gallery (Movies/VideoVault)"); refresh() }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not save: ${e.message}") }
            }
        }.start()
    }

    private fun importExisting() {
        if (!Environment.isExternalStorageManager()) {
            toast("Allow \"All files access\" first")
            return
        }
        AlertDialog.Builder(this).setTitle("Move existing videos?")
            .setMessage(
                "All videos now in the WhatsApp video folder will be moved into the vault. " +
                    "WhatsApp chats will then show them as missing. Continue?"
            )
            .setPositiveButton("Move") { _, _ ->
                Thread {
                    var n = 0
                    for (folder in Vault.sourceFolders()) {
                        folder.listFiles()?.forEach { if (Vault.isVideo(it) && Vault.capture(this, it)) n++ }
                    }
                    runOnUiThread { toast("Moved $n video(s) into the vault"); refresh() }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }
}

class PlayerActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val path = intent.getStringExtra("path") ?: return finish()
        val vv = VideoView(this)
        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoPath(path)
        vv.setOnPreparedListener { it.start() }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        setContentView(frame)
    }
}
