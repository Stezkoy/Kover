package com.ark.kover.core.translation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ark.kover.core.backup.BackupRepository
import com.ark.kover.core.launcher.SwitchableLauncherAdapter
import com.ark.kover.core.model.Rom
import com.ark.kover.core.network.hasInternet
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stop a bulk run when every provider keeps failing instead of marking hundreds of games. */
private const val MAX_CONSECUTIVE_FAILURES = 5

/**
 * Bulk description translation in an application-wide scope.
 *
 * Like [com.ark.kover.core.launcher.AssetApplyService], the job must survive leaving the
 * screen: translating hundreds of descriptions takes minutes. The launcher adapter and the
 * target folder are captured at start, so switching launcher or settings mid-run cannot
 * redirect the writes.
 *
 * Only the description changes. Name, genre, developer, dates and artwork stay untouched;
 * an empty description is skipped, and text that already looks like the target language is
 * skipped too. Backups follow Settings -> Backups: when enabled, the first metadata snapshot
 * is written before the description is replaced.
 */
@Singleton
class TranslationSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launchers: SwitchableLauncherAdapter,
    private val translator: Translator,
    private val backups: BackupRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    var isRunning by mutableStateOf(false)
        private set
    var isStopping by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var cancelled by mutableStateOf(false)
        private set
    var target by mutableStateOf("")
        private set
    var total by mutableStateOf(0)
        private set
    var processed by mutableStateOf(0)
        private set
    var translated by mutableStateOf(0)
        private set
    var skippedEmpty by mutableStateOf(0)
        private set
    var skippedSame by mutableStateOf(0)
        private set
    var failed by mutableStateOf(0)
        private set
    var networkError by mutableStateOf(false)
        private set
    var providerError by mutableStateOf(false)
        private set
    var currentGame by mutableStateOf("")
        private set

    fun start(
        launcherType: String,
        launcherDirUri: Uri,
        roms: List<Rom>,
        targetLanguage: String,
    ) {
        if (isRunning) return
        val snapshot = roms.toList()
        val launcher = launchers.adapterFor(launcherType)
        val resolver = context.contentResolver

        target = targetLanguage
        total = snapshot.size
        processed = 0
        translated = 0
        skippedEmpty = 0
        skippedSame = 0
        failed = 0
        cancelled = false
        finished = false
        currentGame = ""
        isStopping = false
        networkError = false
        providerError = false
        if (!context.hasInternet()) {
            networkError = true
            finished = true
            return
        }
        isRunning = true

        job = scope.launch {
            var wasCancelled = false
            var consecutiveFailures = 0
            try {
                run@ for ((system, systemRoms) in snapshot.groupBy { it.system }) {
                    // One batch read per system: ES-DE/Custom parse the gamelist once instead
                    // of re-reading the whole XML for every game in the platform.
                    val systemMetadata = withContext(Dispatchers.IO) {
                        launcher.readMetadataForSystem(
                            resolver,
                            launcherDirUri,
                            system,
                            systemRoms,
                        )
                    }
                    for (rom in systemRoms) {
                        ensureActive()
                        currentGame = rom.name

                        val meta = systemMetadata[rom.relativePath]
                        if (meta == null) {
                            skippedEmpty++
                            processed++
                            continue
                        }

                        val source = meta.effectiveDescription()
                        if (source.isBlank()) {
                            skippedEmpty++
                            processed++
                            continue
                        }
                        if (looksLikeTargetLanguage(source, targetLanguage)) {
                            skippedSame++
                            processed++
                            continue
                        }

                        val outcome = withContext(Dispatchers.IO) {
                            try {
                                translator.translateWithOutcome(source, "auto", targetLanguage)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e(
                                    "AutoTranslate",
                                    "translate failed for ${rom.relativePath}",
                                    e,
                                )
                                TranslationOutcome.Failed
                            }
                        }

                        when (outcome) {
                            is TranslationOutcome.Translated -> {
                                consecutiveFailures = 0
                                // Do not write when the user pressed Stop while the request was running.
                                ensureActive()
                                val updated = meta.copy(desc = outcome.text)
                                val ok = withContext(Dispatchers.IO) {
                                    try {
                                        backups.saveMetadataBackup(
                                            resolver,
                                            launcherDirUri,
                                            rom.system,
                                            rom.relativePath,
                                            meta,
                                            updated,
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        android.util.Log.e(
                                            "AutoTranslate",
                                            "backup failed for ${rom.relativePath}",
                                            e,
                                        )
                                    }
                                    try {
                                        launcher.writeMetadata(
                                            resolver,
                                            launcherDirUri,
                                            rom.system,
                                            rom.relativePath,
                                            updated,
                                        )
                                        true
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        android.util.Log.e(
                                            "AutoTranslate",
                                            "write failed for ${rom.relativePath}",
                                            e,
                                        )
                                        false
                                    }
                                }
                                if (ok) {
                                    translated++
                                    android.util.Log.d(
                                        "AutoTranslate",
                                        "translated ${rom.relativePath} -> $targetLanguage",
                                    )
                                } else {
                                    failed++
                                }
                            }

                            TranslationOutcome.SameLanguage -> {
                                consecutiveFailures = 0
                                skippedSame++
                            }

                            TranslationOutcome.Failed -> {
                                failed++
                                consecutiveFailures++
                                if (!context.hasInternet()) {
                                    networkError = true
                                    android.util.Log.w(
                                        "AutoTranslate",
                                        "network lost, stopping the run",
                                    )
                                    break@run
                                }
                                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                    providerError = true
                                    android.util.Log.w(
                                        "AutoTranslate",
                                        "provider failed $consecutiveFailures times, stopping the run",
                                    )
                                    break@run
                                }
                            }
                        }

                        processed++
                        delay(300)
                    }
                }
            } catch (e: CancellationException) {
                wasCancelled = true
            } catch (e: Exception) {
                android.util.Log.e("AutoTranslate", "run failed", e)
            } finally {
                withContext(NonCancellable) {
                    cancelled = wasCancelled
                    finished = true
                    isRunning = false
                    isStopping = false
                    currentGame = ""
                }
            }
        }
    }

    fun cancel() {
        if (!isRunning || isStopping) return
        isStopping = true
        job?.cancel()
    }

    /** Drops the last run's report, e.g. after switching to another launcher. */
    fun clearReport() {
        if (isRunning) return
        finished = false
        cancelled = false
        total = 0
        processed = 0
        translated = 0
        skippedEmpty = 0
        skippedSame = 0
        failed = 0
        networkError = false
        providerError = false
        currentGame = ""
    }
}
