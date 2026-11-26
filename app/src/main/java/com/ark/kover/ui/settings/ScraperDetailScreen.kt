package com.ark.kover.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ark.kover.R
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.FocusableCard
import com.ark.kover.ui.components.KoverButton
import com.ark.kover.ui.components.KoverButtonStyle
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface TestResult {
    data class Ok(
        val quotaRemaining: Int? = null,
        val quotaLimit: Int? = null,
        val unlimited: Boolean = false,
        val guest: Boolean = false,
    ) : TestResult

    data class Error(val detail: String) : TestResult
}

@HiltViewModel
class ScraperDetailViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val neoAssets: com.ark.kover.scraper.NeoAssetsScraper,
    private val httpClient: okhttp3.OkHttpClient,
) : ViewModel() {
    val settings = settingsRepo.settings

    var field1 by mutableStateOf("")
    var saved by mutableStateOf(false)
        private set
    var testRunning by mutableStateOf(false)
        private set
    var testResult by mutableStateOf<TestResult?>(null)
        private set

    fun load(current: AppSettings, scraperId: String) {
        when (scraperId) {
            "neoassets" -> field1 = current.neoAssetsBearerKey
            "steamgriddb" -> field1 = current.steamGridDbToken
            "rawg" -> field1 = current.rawgApiKey
        }
    }

    fun save(scraperId: String) {
        viewModelScope.launch {
            settingsRepo.update { current ->
                when (scraperId) {
                    "neoassets" -> current.copy(neoAssetsBearerKey = field1.trim())
                    "steamgriddb" -> current.copy(steamGridDbToken = field1.trim())
                    "rawg" -> current.copy(rawgApiKey = field1.trim())
                    else -> current
                }
            }
            saved = true
        }
    }

    fun test(scraperId: String) {
        if (testRunning) return
        testRunning = true
        testResult = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (scraperId) {
                    "neoassets" -> {
                        val info = neoAssets.accountInfo(field1.trim())
                        if (info == null) {
                            TestResult.Error("HTTP")
                        } else {
                            TestResult.Ok(
                                quotaRemaining = info.remaining,
                                quotaLimit = info.limit,
                                unlimited = info.unlimited,
                                guest = info.subject != "user",
                            )
                        }
                    }

                    "steamgriddb" -> {
                        val token = field1.trim()
                        if (token.isBlank()) {
                            TestResult.Error("no token")
                        } else {
                            runCatching {
                                val request = okhttp3.Request.Builder()
                                    .url("https://www.steamgriddb.com/api/v2/search/autocomplete/sonic")
                                    .header("Authorization", "Bearer $token")
                                    .build()
                                httpClient.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) TestResult.Ok()
                                    else TestResult.Error("HTTP ${response.code}")
                                }
                            }.getOrElse { TestResult.Error(it.message ?: "error") }
                        }
                    }

                    "rawg" -> {
                        val key = field1.trim()
                        if (key.isBlank()) {
                            TestResult.Error("no token")
                        } else {
                            runCatching {
                                val request = okhttp3.Request.Builder()
                                    .url("https://api.rawg.io/api/games?key=$key&search=sonic&page_size=1")
                                    .build()
                                httpClient.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) TestResult.Ok()
                                    else TestResult.Error("HTTP ${response.code}")
                                }
                            }.getOrElse { TestResult.Error(it.message ?: "error") }
                        }
                    }

                    "steam" -> {
                        runCatching {
                            val request = okhttp3.Request.Builder()
                                .url("https://store.steampowered.com/api/storesearch/?term=sonic&l=english&cc=us")
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Kover")
                                .build()
                            httpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) TestResult.Ok()
                                else TestResult.Error("HTTP ${response.code}")
                            }
                        }.getOrElse { TestResult.Error(it.message ?: "error") }
                    }

                    else -> TestResult.Error("not implemented")
                }
            }
            testRunning = false
            testResult = result
        }
    }
}

@Composable
fun ScraperDetailScreen(
    scraperId: String,
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
    viewModel: ScraperDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    LaunchedEffect(scraperId, settings.neoAssetsBearerKey, settings.steamGridDbToken) {
        viewModel.load(settings, scraperId)
    }

    val title = when (scraperId) {
        "neoassets" -> "NeoAssets"
        "steamgriddb" -> "SteamGridDB"
        "libretro" -> "libretro-thumbnails"
        "steam" -> "Steam Store"
        "rawg" -> "RAWG"
        "screenscraper" -> "ScreenScraper"
        else -> stringResource(R.string.nav_scrapers)
    }

    AppScaffold(
        selectedTab = KoverTab.Scrapers,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(stringResource(R.string.nav_scrapers), title),
        onBack = onBack,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.scrapers_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle(text = stringResource(R.string.scraper_howto))
        when (scraperId) {
            "neoassets" -> {
                Text(
                    text = stringResource(R.string.neo_howto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow(
                    title = "neoassets.dev",
                    subtitle = stringResource(R.string.neo_open_site),
                    onClick = { openUrl(context, "https://neoassets.dev/app") },
                )
                SecretField(
                    label = stringResource(R.string.scraper_key_label),
                    value = viewModel.field1,
                    onChange = { viewModel.field1 = it },
                )
                Text(
                    text = stringResource(R.string.neo_guest_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            "steamgriddb" -> {
                Text(
                    text = stringResource(R.string.sgdb_howto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow(
                    title = "steamgriddb.com",
                    subtitle = stringResource(R.string.sgdb_open_site),
                    onClick = { openUrl(context, "https://www.steamgriddb.com/profile/preferences/api") },
                )
                SecretField(
                    label = stringResource(R.string.scraper_key_label),
                    value = viewModel.field1,
                    onChange = { viewModel.field1 = it },
                )
            }

            "libretro" -> {
                Text(
                    text = stringResource(R.string.libretro_howto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow(
                    title = "github.com/libretro-thumbnails",
                    subtitle = stringResource(R.string.libretro_open_site),
                    onClick = { openUrl(context, "https://github.com/libretro-thumbnails") },
                )
            }

            "steam" -> {
                Text(
                    text = stringResource(R.string.steam_howto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow(
                    title = "store.steampowered.com",
                    subtitle = stringResource(R.string.steam_open_site),
                    onClick = { openUrl(context, "https://store.steampowered.com/") },
                )
            }

            "rawg" -> {
                Text(
                    text = stringResource(R.string.rawg_howto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow(
                    title = "rawg.io/apidocs",
                    subtitle = stringResource(R.string.rawg_open_site),
                    onClick = { openUrl(context, "https://rawg.io/apidocs") },
                )
                SecretField(
                    label = stringResource(R.string.scraper_key_label),
                    value = viewModel.field1,
                    onChange = { viewModel.field1 = it },
                )
            }

            else -> {
                Text(
                    text = stringResource(R.string.scrapers_soon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (scraperId == "neoassets" || scraperId == "steamgriddb" ||
            scraperId == "rawg" || scraperId == "steam"
        ) {
            SectionTitle(text = stringResource(R.string.scraper_connection))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap6),
            ) {
                KoverButton(
                    label = if (viewModel.saved) stringResource(R.string.scrapers_saved)
                    else stringResource(R.string.common_save),
                    onClick = { viewModel.save(scraperId) },
                    style = KoverButtonStyle.Primary,
                )
                KoverButton(
                    label = if (viewModel.testRunning) stringResource(R.string.scraper_testing)
                    else stringResource(R.string.scraper_test),
                    onClick = { viewModel.test(scraperId) },
                    style = KoverButtonStyle.Secondary,
                )
            }
            TestStatus(viewModel.testResult)
        }
    }
}

@Composable
private fun TestStatus(result: TestResult?) {
    if (result == null) return
    val okText = stringResource(R.string.scraper_test_ok)
    val guestText = stringResource(R.string.neo_guest_hint)
    val text = when (result) {
        is TestResult.Ok -> {
            val parts = mutableListOf(okText)
            when {
                result.unlimited -> parts += stringResource(R.string.scraper_quota_unlimited)
                result.quotaLimit != null && result.quotaRemaining != null -> parts += stringResource(
                    R.string.scraper_quota,
                    result.quotaRemaining,
                    result.quotaLimit,
                )
            }
            if (result.guest) parts += guestText
            parts.joinToString(" · ")
        }

        is TestResult.Error -> stringResource(R.string.scraper_test_error, result.detail)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (result is TestResult.Ok) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().height(54.dp),
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
