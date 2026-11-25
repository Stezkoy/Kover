package com.ark.kover.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

/**
 * A file-like abstraction over SAF content:// URIs.
 * Replaces java.io.File for SAF-based storage access.
 *
 * Supports: list children, read bytes, write bytes, delete, exists, isDirectory.
 */
class ContentBridge(
    private val resolver: ContentResolver,
) {

    // --- Exists / type ---

    fun exists(uri: Uri): Boolean {
        // For tree URIs, check if we can list children
        if (uri.path?.contains("/tree/") == true) {
            return listChildren(uri).isNotEmpty()
        }
        // For regular URIs, try query
        try {
            val c = resolver.query(uri, null, null, null, null) ?: return false
            c.use { return it.moveToFirst() }
        } catch (e: Exception) { return false }
    }

    fun isDirectory(uri: Uri): Boolean {
        // For tree URIs, it's always a directory
        if (uri.path?.contains("/tree/") == true) {
            return true
        }
        try {
            val c = resolver.query(uri, null, null, null, null) ?: return false
            c.use {
                val mimeCol = DocumentsContract.Document.COLUMN_MIME_TYPE
                if (it.moveToFirst()) {
                    val mime = it.getString(it.getColumnIndexOrThrow(mimeCol))
                    return mime == DocumentsContract.Document.MIME_TYPE_DIR
                }
                return false
            }
        } catch (e: Exception) { return false }
    }

    fun isFile(uri: Uri): Boolean {
        try {
            val c = resolver.query(uri, null, null, null, null) ?: return false
            c.use {
                val mimeCol = DocumentsContract.Document.COLUMN_MIME_TYPE
                if (it.moveToFirst()) {
                    val mime = it.getString(it.getColumnIndexOrThrow(mimeCol))
                    return mime != null && mime != DocumentsContract.Document.MIME_TYPE_DIR
                }
                return false
            }
        } catch (e: Exception) { return false }
    }

    // --- Read ---

    fun readBytes(uri: Uri): ByteArray = try {
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    } catch (e: Exception) { ByteArray(0) }

    fun readText(uri: Uri): String? = try {
        resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) { null }

    // --- Write ---

    fun writeBytes(uri: Uri, data: ByteArray) {
        try {
            resolver.openOutputStream(uri)?.use { it.write(data) }
        } catch (e: Exception) { /* ignore */ }
    }

    fun writeText(uri: Uri, text: String) {
        writeBytes(uri, text.toByteArray())
    }

    // --- Delete ---

    fun delete(uri: Uri): Boolean = try {
        resolver.delete(uri, null, null) > 0
    } catch (e: Exception) { false }

    // --- Directory ---

    /**
     * List children of a tree Uri. Returns map: childName -> childUri.
     */
    fun listChildren(treeUri: Uri): Map<String, Uri> {
        val result = mutableMapOf<String, Uri>()
        try {
            // For tree URIs, extract the document ID from the tree path
            // Tree URI format: content://authority/tree/{encodedDocId}
            val treePath = treeUri.path ?: return result
            val treeId = treePath.substringAfterLast("/")
            val decodedTreeId = java.net.URLDecoder.decode(treeId, "UTF-8")
            android.util.Log.d("ContentBridge", "listChildren: treeUri=$treeUri, decodedTreeId=$decodedTreeId")
            
            // Use DocumentsContract.buildChildDocumentsUri
            val childUri = DocumentsContract.buildChildDocumentsUri(
                treeUri.authority, 
                decodedTreeId
            )
            android.util.Log.d("ContentBridge", "listChildren: buildChildDocumentsUri=$childUri")
            
            resolver.query(childUri, null, null, null, null)?.use { c ->
                android.util.Log.d("ContentBridge", "listChildren: children count=${c.count}")
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getString(idCol) ?: continue
                    val name = c.getString(nameCol) ?: ""
                    val childDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    result[name] = childDocumentUri
                }
            }
            
            android.util.Log.d("ContentBridge", "listChildren: found ${result.size} children: ${result.keys}")
        } catch (e: Exception) {
            android.util.Log.e("ContentBridge", "listChildren FAILED", e)
        }
        return result
    }

    /**
     * Build a child Uri from a parent tree Uri and child name.
     */
    fun childUri(parent: Uri, childName: String): Uri? {
        val children = listChildren(parent)
        return children[childName]
    }

    /**
     * Get the last path segment (name) of a Uri.
     */
    fun name(uri: Uri): String = uri.lastPathSegment ?: ""

    /**
     * Create a new document (file) in a directory. Returns the new file Uri or null.
     */
    fun createFile(dirUri: Uri, fileName: String, mimeType: String): Uri? {
        return try {
            val values = android.content.ContentValues().apply {
                put("display_name", fileName)
                put("mime_type", mimeType)
            }
            resolver.insert(dirUri, values)
        } catch (e: Exception) {
            null
        }
    }

    // --- Path helpers ---

    /**
     * Get a Uri for a file named [fileName] in directory [dirUri].
     * Returns existing Uri if found, or creates a new one.
     */
    fun getOrCreateFile(dirUri: Uri, fileName: String, mimeType: String = "application/octet-stream"): Uri? {
        val children = listChildren(dirUri)
        return children[fileName] ?: createFile(dirUri, fileName, mimeType)
    }

    /**
     * Find or create a subdirectory. Returns the dir Uri or null.
     */
    fun getOrCreateDir(parentUri: Uri, dirName: String): Uri? {
        val children = listChildren(parentUri)
        return children[dirName]?.takeIf { isDirectory(it) }
            ?: createDir(parentUri, dirName)
    }

    private fun createDir(parentUri: Uri, dirName: String): Uri? {
        return try {
            val values = android.content.ContentValues().apply {
                put("display_name", dirName)
                put("mime_type", DocumentsContract.Document.MIME_TYPE_DIR)
            }
            resolver.insert(parentUri, values)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find a child by prefix name (for matching game names).
     */
    fun findChildByPrefix(dirUri: Uri, prefix: String): Pair<String, Uri>? {
        val children = listChildren(dirUri)
        return children.entries.find { it.key.startsWith(prefix) }
            ?.let { (name, uri) -> name to uri }
    }

    // --- Recursive walk ---

    /**
     * Walk all files under a tree Uri (recursive). Returns list of (relativePath, uri).
     */
    fun walkFiles(treeUri: Uri, prefix: String = ""): List<Pair<String, Uri>> {
        val result = mutableListOf<Pair<String, Uri>>()
        val children = listChildren(treeUri)
        for ((name, childUri) in children) {
            val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (isDirectory(childUri)) {
                result.addAll(walkFiles(childUri, relPath))
            } else {
                result.add(relPath to childUri)
            }
        }
        return result
    }
}