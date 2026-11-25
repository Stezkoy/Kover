package com.ark.kover

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ark.kover.core.settings.AppSettings
import com.ark.kover.core.settings.SettingsRepository
import com.ark.kover.core.update.UpdateRepository
import com.ark.kover.ui.KoverNavHost
import com.ark.kover.ui.theme.KoverTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    val updates: UpdateRepository,
) : ViewModel() {
    val settings = settingsRepo.settings
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val darkTheme = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            androidx.compose.runtime.LaunchedEffect(settings.themeMode) {
                viewModel.updates.checkOnStartIfEnabled()
            }
            KoverTheme(darkTheme = darkTheme, presetId = settings.themePreset) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        KoverNavHost()
                        com.ark.kover.ui.components.UpdateDialog(viewModel.updates)
                    }
                }
            }
        }
    }
}
