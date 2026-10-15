package io.github.openfinalshell.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** FinalShell keeps connection JSON in the selected directory and in its `conn` child. */
object FinalShellScanner {
    const val MAX_FILES = 5_000
    const val MAX_FILE_BYTES = 1024 * 1024

    data class Batch(val records: List<Any?>, val files: Int)

    fun scan(resolver: ContentResolver, treeUri: Uri): Batch {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val records = mutableListOf<Any?>()
        var files = 0
        fun consume(documentId: String) {
            for (child in children(resolver, treeUri, documentId)) {
                if (files >= MAX_FILES) return
                if (child.directory || !child.name.endsWith(".json", ignoreCase = true)) continue
                if (child.size > MAX_FILE_BYTES) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                val bytes = resolver.openInputStream(uri)?.use { readLimited(it, MAX_FILE_BYTES) } ?: continue
                files++
                val parsed = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
                when (val plain = plain(parsed)) {
                    is List<*> -> records.addAll(plain)
                    else -> records.add(plain)
                }
            }
        }
        consume(rootId)
        val conn = children(resolver, treeUri, rootId).firstOrNull { it.directory && it.name.equals("conn", ignoreCase = true) }
        if (conn != null) consume(conn.id)
        return Batch(records, files)
    }

    private data class Child(val id: String, val name: String, val directory: Boolean, val size: Long)

    private fun children(resolver: ContentResolver, treeUri: Uri, documentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val rows = mutableListOf<Child>()
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val size = if (cursor.isNull(sizeColumn)) -1L else cursor.getLong(sizeColumn)
                rows += Child(
                    cursor.getString(idColumn),
                    cursor.getString(nameColumn).orEmpty(),
                    cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR,
                    size
                )
            }
        }
        return rows
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > max) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" || element.content == "false" -> element.booleanOrNull
            element.longOrNull != null && '.' !in element.content && 'e' !in element.content.lowercase() -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
        is JsonArray -> element.map { plain(it) }
        is JsonObject -> element.entries.associate { it.key to plain(it.value) }
    }
}
