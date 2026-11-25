package com.ark.kover.core.launcher

import android.net.Uri
import java.io.File

/**
 * Resolves SAF tree URIs to plain files. Works for the primary volume and SD cards
 * when MANAGE_EXTERNAL_STORAGE is granted.
 */
object EsDePaths {

    fun resolve(uri: Uri): File? = try {
        when {
            uri.scheme == "content" && uri.path?.contains("/tree/") == true -> {
                val treeId = uri.path!!.substringAfterLast("/")
                val decoded = java.net.URLDecoder.decode(treeId, "UTF-8")
                val colon = decoded.indexOf(':')
                if (colon > 0) {
                    val volume = decoded.substring(0, colon)
                    val path = decoded.substring(colon + 1)
                    val base = when (volume) {
                        "primary" -> "/storage/emulated/0"
                        else -> "/storage/$volume"
                    }
                    File("$base/$path")
                } else {
                    File("/storage/emulated/0/$decoded")
                }
            }
            uri.scheme == "file" -> File(uri.path ?: "")
            else -> File(uri.toString())
        }
    } catch (e: Exception) {
        android.util.Log.e("EsDePaths", "cannot resolve $uri", e)
        null
    }

    fun defaultRoot(): File = File("/storage/emulated/0/ES-DE")

    /** Turns a SAF tree URI into a readable filesystem path. */
    fun prettyPath(uri: Uri): String {
        val raw = uri.toString()
        if (uri.scheme != "content" || uri.path?.contains("/tree/") != true) return raw
        return try {
            val treeId = uri.path!!.substringAfterLast('/')
            val decoded = java.net.URLDecoder.decode(treeId, "UTF-8")
            val colon = decoded.indexOf(':')
            if (colon > 0) {
                val volume = decoded.substring(0, colon)
                val path = decoded.substring(colon + 1).trim('/')
                val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
                if (path.isEmpty()) base else "$base/$path"
            } else {
                decoded
            }
        } catch (e: Exception) {
            raw
        }
    }
}
