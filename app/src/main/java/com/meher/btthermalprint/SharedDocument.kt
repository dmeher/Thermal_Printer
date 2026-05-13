package com.meher.btthermalprint

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.TextUtils
import java.io.ByteArrayOutputStream

sealed class SharedDocument {
    data class PlainText(val value: String) : SharedDocument()
    data class FileRef(val uri: Uri, val name: String, val mimeType: String?) : SharedDocument()

    companion object {
        fun fromIntent(context: Context, intent: Intent): List<SharedDocument> {
            val docs = mutableListOf<SharedDocument>()
            if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                    docs += PlainText(it)
                }

                val single = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                if (single != null) docs += single.toFileRef(context)

                val many = intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM).orEmpty()
                docs += many.map { it.toFileRef(context) }
            }
            return docs.distinctBy {
                when (it) {
                    is PlainText -> "text:${it.value}"
                    is FileRef -> "uri:${it.uri}"
                }
            }
        }

        private fun Uri.toFileRef(context: Context): FileRef {
            val resolver = context.contentResolver
            return FileRef(this, resolver.displayName(this), resolver.getType(this))
        }
    }
}

fun SharedDocument.toEscPosJobs(context: Context, paperWidthPx: Int): List<ByteArray> = when (this) {
    is SharedDocument.PlainText -> listOf(EscPosEncoder.text(value))
    is SharedDocument.FileRef -> when {
        mimeType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> renderPdf(context, uri, paperWidthPx)
        mimeType?.startsWith("image/") == true -> listOf(EscPosEncoder.bitmap(context.decodeBitmap(uri), paperWidthPx))
        name.endsWith(".txt", ignoreCase = true) -> listOf(EscPosEncoder.text(context.readText(uri)))
        else -> listOf(EscPosEncoder.text("Unsupported file: $name\n"))
    }
}

private fun Context.decodeBitmap(uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
            decoder.isMutableRequired = false
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream) ?: error("Could not decode image")
        }
    }
}

private fun Context.readText(uri: Uri): String {
    return contentResolver.openInputStream(uri).use { stream ->
        stream?.bufferedReader()?.readText().orEmpty()
    }
}

private fun renderPdf(context: Context, uri: Uri, paperWidthPx: Int): List<ByteArray> {
    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Could not open PDF")
    return pfd.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            (0 until renderer.pageCount).map { index ->
                renderer.openPage(index).use { page ->
                    val ratio = paperWidthPx.toFloat() / page.width.toFloat()
                    val height = (page.height * ratio).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(paperWidthPx, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    EscPosEncoder.bitmap(bitmap, paperWidthPx)
                }
            }
        }
    }
}

private fun ContentResolver.displayName(uri: Uri): String {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment?.takeIf { !TextUtils.isEmpty(it) } ?: "shared-file"
}

@Suppress("DEPRECATION")
private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(name: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, T::class.java) else getParcelableArrayListExtra(name)
}
