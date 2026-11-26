package com.ark.kover.ui.translate

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-translate selection that survives leaving the screen (but not a process restart).
 * The running job itself lives in [com.ark.kover.core.translation.TranslationSession];
 * this only keeps the wizard state so returning to the tab does not lose the platforms.
 */
@Singleton
class TranslateDraft @Inject constructor() {
    var launcher: String = ""
    var selectedSystems: Set<String> = emptySet()
    var page: Int = 0
}
