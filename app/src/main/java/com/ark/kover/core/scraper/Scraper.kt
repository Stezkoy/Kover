package com.ark.kover.core.scraper

import com.ark.kover.core.model.Asset
import com.ark.kover.core.model.GameCandidate
import com.ark.kover.core.model.MatchResult

/**
 * A scraper source for game metadata and assets.
 * Each implementation handles one external service.
 */
interface Scraper {
    val id: String
    val displayName: String

    /** Check if credentials are configured. */
    fun isConfigured(): Boolean

    /** Whether this source knows the given system folder id (e.g. "snes", "switch"). */
    fun supportsSystem(system: String): Boolean = true

    /**
     * Search for a game by name and/or ROM hash.
     * Returns null if not found.
     */
    suspend fun search(
        system: String,
        name: String,
        md5: String? = null,
    ): MatchResult?

    /**
     * Search and return multiple lightweight candidates so the user can pick.
     * Default implementation: returns a single candidate if search() finds one.
     */
    suspend fun searchCandidates(
        system: String,
        name: String,
        md5: String? = null,
    ): List<GameCandidate> {
        return search(system, name, md5)?.let {
            listOf(GameCandidate(id = it.gameName, name = it.gameName, source = id,
                system = system, matchedBy = it.matchedBy, confidence = it.confidence))
        } ?: emptyList()
    }

    /**
     * Fetch full metadata + assets for a specific candidate (gameId = remote id).
     */
    suspend fun fetchFor(
        system: String,
        gameId: String,
    ): MatchResult? = null

    /**
     * Fetch additional assets for a known game (if search returned partial data).
     */
    suspend fun fetchAssets(
        system: String,
        gameId: String,
        kinds: List<String> = emptyList(),
    ): List<Asset>
}
