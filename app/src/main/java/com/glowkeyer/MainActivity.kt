package com.glowkeyer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private var videoUri: Uri? = null
    private var treeUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var fpsBox: EditText
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val pickVideo = Button(this)
        pickVideo.text = "1. Pick video"
        pickVideo.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "video/*"
            startActivityForResult(i, 1)
        }

        val pickFolder = Button(this)
        pickFolder.text = "2. Pick output folder"
        pickFolder.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            startActivityForResult(i, 2)
        }

        fpsBox = EditText(this)
        fpsBox.hint = "Frames per second (empty = 30)"
        fpsBox.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        val start = Button(this)
        start.text = "3. Start"
        start.setOnClickListener {
            if (running) return@setOnClickListener
            if (videoUri == null || treeUri == null) {
                status.text = "Pick a video and an output folder first."
                return@setOnClickListener
            }
            running = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Thread { process() }.start()
        }

        status = TextView(this)
        status.text = "Pick a video and a folder, then press Start."
        status.textSize = 16f

        root.addView(pickVideo)
        root.addView(pickFolder)
        root.addView(fpsBox)
        root.addView(start)
        root.addView(status)
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == 1) {
            videoUri = uri
            status.text = "Video selected."
        } else if (requestCode == 2) {
            treeUri = uri
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) { }
            status.text = "Output folder selected."
        }
    }

    private fun say(s: String) = runOnUiThread { status.text = s }

    // Black background -> transparency, glow kept:
    // alpha = max(R,G,B), color = RGB / alpha. Over black it looks identical to the original.
    private fun process() {
        val r = MediaMetadataRetriever()
        try {
            val video = videoUri!!
            val tree = treeUri!!
            r.setDataSource(this, video)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val fps = (fpsBox.text.toString().toDoubleOrNull() ?: 30.0).coerceIn(1.0, 120.0)
            val total = (durMs / 1000.0 * fps).toInt().coerceAtLeast(1)

            val resolver = contentResolver
            val treeDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val folderName = "glow_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = DocumentsContract.createDocument(
                resolver, treeDoc, DocumentsContract.Document.MIME_TYPE_DIR, folderName
            )!!

            var px = IntArray(0)
            for (i in 0 until total) {
                val t = (i / fps * 1_000_000).toLong()
                val src = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val w = src.width
                val h = src.height
                if (px.size != w * h) px = IntArray(w * h)
                src.getPixels(px, 0, w, 0, 0, w, h)
                for (k in px.indices) {
                    val c = px[k]
                    val rr = (c shr 16) and 255
                    val gg = (c shr 8) and 255
                    val bb = c and 255
                    val a = maxOf(rr, maxOf(gg, bb))
                    px[k] = if (a == 0) 0 else
                        (a shl 24) or ((rr * 255 / a) shl 16) or ((gg * 255 / a) shl 8) or (bb * 255 / a)
                }
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                out.setPixels(px, 0, w, 0, 0, w, h)
                val file = DocumentsContract.createDocument(
                    resolver, dir, "image/png", String.format(Locale.US, "frame_%05d.png", i + 1)
                )!!
                resolver.openOutputStream(file)!!.use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                src.recycle()
                out.recycle()
                say("Frame ${i + 1} / $total  (${w}x$h)")
            }
            say("Done. $total frames saved in folder: $folderName")
        } catch (e: Throwable) {
            say("Error: ${e.message}")
        } finally {
            r.release()
            running = false
            runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}
