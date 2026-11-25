package com.ark.kover.core.matcher

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Normalizes ROM file names into clean game names.
 * "Chrono Trigger (USA) [!].sfc" -> "Chrono Trigger"
 * "Mario Kart 8 Deluxe [0100152000022000][v0] (6.77 GB).nsp" -> "Mario Kart 8 Deluxe"
 */
@Singleton
class NameNormalizer @Inject constructor() {

    fun normalize(fileName: String): String {
        var name = fileName
            .substringBeforeLast('.')  // remove extension
            .trim()

        // Remove common ROM metadata patterns
        name = name.replace(Regex("\\s*\\[[^\\]]*\\]"), "")      // [USA], [!], [0100152000022000]
        name = name.replace(Regex("\\s*\\([^)]*\\)"), "")        // (USA), (6.77 GB), (Japan)
        name = name.replace(Regex("\\s*\\{[^}]*\\}"), "")        // {anything}

        // Double extensions: "Fable (Russian).xiso.iso" keeps ".xiso" after removing ".iso".
        var stripped = true
        while (stripped) {
            stripped = false
            val dot = name.lastIndexOf('.')
            if (dot > 0) {
                val inner = name.substring(dot + 1).lowercase()
                if (inner in FORMAT_SUFFIXES) {
                    name = name.substring(0, dot)
                    stripped = true
                }
            }
        }

        // Remove trailing version/region markers
        name = name.replace(Regex("\\s*[vV]\\d+.*$"), "")
        name = name.replace(Regex("\\s*[\\-_.]\\s*(USA|EUR|JPN|JAP|World|WRL|PAL|NTSC|PAL60|NTSC60).*$", RegexOption.IGNORE_CASE), "")

        // Remove leading zeros, extra spaces
        name = name.replace(Regex("\\s{2,}"), " ")
            .trim()
            .let { it.trim(' ', '.', '_', '-') }

        return name
    }

    /**
     * Extract system id from a relative path: "switch/Mario Kart.nsp" -> "switch"
     */
    fun extractSystem(relativePath: String): String {
        val first = relativePath.substringBefore('/', relativePath)
        return first.lowercase()
    }

    /**
     * Generate a stable id from a relative path (for Room key).
     */
    fun romId(relativePath: String): String {
        return relativePath.replace(Regex("[^a-zA-Z0-9]"), "_").lowercase()
    }

    companion object {
        /** Format tokens that can appear before the real extension (".xiso.iso", ".cue.bin", …). */
        private val FORMAT_SUFFIXES = setOf(
            "xiso", "iso", "bin", "cue", "chd", "rvz", "gcm", "wbfs", "wia", "cso", "ecm",
            "zip", "7z", "rar", "gz", "tar", "nsp", "xci", "nca", "wud", "wux", "wad",
            "dol", "elf", "pbp", "m3u", "sfc", "smc", "z64", "nds", "gba", "gbc", "cia",
            "cci", "3ds", "nes", "fds", "xex", "rom", "apk", "xbe",
        )
    }
}
