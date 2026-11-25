package com.ark.kover.core.matcher

import android.content.ContentResolver
import android.net.Uri
import com.ark.kover.data.KoverDatabase
import com.ark.kover.data.RomHashEntity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes and caches MD5 hashes of ROM files via ContentResolver.
 * Works with both file:// and content:// URIs.
 */
@Singleton
class RomHasher @Inject constructor(
    private val db: KoverDatabase,
) {

    /**
     * Get MD5 for a ROM file. Computes if not cached.
     * @param resolver ContentResolver for reading the file
     * @param fileUri Uri of the ROM file (file:// or content://)
     * @param relativePath relative path for caching
     * @param system system for caching
     */
    suspend fun getMd5(
        resolver: ContentResolver,
        fileUri: Uri,
        relativePath: String,
        system: String,
    ): String {
        // Check cache
        val cached = db.romHashDao().getByPath(relativePath)
        if (cached != null) return cached.md5

        // Compute
        val md5 = computeMd5(resolver, fileUri)

        // Cache
        db.romHashDao().upsert(
            RomHashEntity(
                relativePath = relativePath,
                system = system,
                md5 = md5,
                size = 0L, // SAF doesn't give size easily; could query size via content provider
            )
        )
        return md5
    }

    private fun computeMd5(resolver: ContentResolver, uri: Uri): String {
        val digest = MessageDigest.getInstance("MD5")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
