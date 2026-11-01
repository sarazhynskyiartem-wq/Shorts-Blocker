/*
 * Copyright 2025 Atick Faisal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.atick.shorts.services

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber
import java.io.File

/**
 * TEMPORARY DEBUG HELPER — remove once Reels detection is finalised.
 *
 * Dumps the accessibility node tree (view ids, content descriptions, text, class,
 * selected state) of a screen to `Downloads/shorts-blocker-dump.txt` so the exact
 * identifiers of Instagram's Reels player UI can be inspected without adb/a PC.
 * Dumps are appended, each prefixed with a header, so several screens can be
 * captured in one session.
 */
object DebugTreeDumper {

    private const val FILE_NAME = "shorts-blocker-dump.txt"
    private const val MAX_NODES = 600

    /**
     * Walks [root] on the calling (accessibility) thread while the nodes are still
     * valid and returns a compact textual dump. Only nodes carrying an id, text or
     * content description are printed, to keep the output readable.
     */
    fun capture(root: AccessibilityNodeInfo, header: String): String {
        val sb = StringBuilder()
        sb.append("===== ").append(header).append(" @ ").append(System.currentTimeMillis()).append(" =====\n")
        var scanned = 0
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty() && scanned < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            scanned++
            val id = node.viewIdResourceName
            val text = node.text
            val desc = node.contentDescription
            if (id != null || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
                sb.append("  ".repeat(depth.coerceAtMost(12)))
                    .append("id=").append(id)
                    .append(" | cls=").append(node.className)
                    .append(" | text=").append(text)
                    .append(" | desc=").append(desc)
                    .append(" | sel=").append(node.isSelected)
                    .append('\n')
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.add(it to depth + 1) }
            }
        }
        sb.append("----- ").append(scanned).append(" nodes -----\n\n")
        return sb.toString()
    }

    /** Appends [text] to the dump file. Safe to call off the main thread. */
    fun append(context: Context, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendViaMediaStore(context, text)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, FILE_NAME).appendText(text)
            }
        } catch (e: Exception) {
            Timber.e(e, "[DebugTreeDumper] failed to write dump")
        }
    }

    private fun appendViaMediaStore(context: Context, text: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var uri = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(FILE_NAME),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
        if (uri == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            uri = resolver.insert(collection, values)
            uri?.let { resolver.openOutputStream(it, "w")?.use { os -> os.write(text.toByteArray()) } }
        } else {
            resolver.openOutputStream(uri, "wa")?.use { os -> os.write(text.toByteArray()) }
        }
    }
}
