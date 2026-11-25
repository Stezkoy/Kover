package com.ark.kover.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ark.kover.core.library.LibraryRepository
import com.ark.kover.ui.game.EditAssetsScreen
import com.ark.kover.ui.game.EditMetadataScreen
import com.ark.kover.ui.game.RomCardScreen
import com.ark.kover.ui.backup.BackupsScreen
import com.ark.kover.ui.library.LibraryScreen
import com.ark.kover.ui.library.RomListScreen
import com.ark.kover.ui.settings.GeneralSettingsScreen
import com.ark.kover.ui.settings.LauncherDetailScreen
import com.ark.kover.ui.settings.LauncherListScreen
import com.ark.kover.ui.settings.ScraperDetailScreen
import com.ark.kover.ui.settings.ScraperListScreen

sealed class Screen(val route: String) {
    object Library : Screen("library")
    object RomList : Screen("roms/{system}") {
        fun create(system: String) = "roms/${android.net.Uri.encode(system)}"
    }
    object RomCard : Screen("rom")
    object EditAssets : Screen("rom/assets?slot={slot}") {
        fun create(slot: String? = null) = "rom/assets?slot=${slot.orEmpty()}"
    }
    object EditMetadata : Screen("rom/metadata")
    object Scrapers : Screen("scrapers")
    object ScraperDetail : Screen("scrapers/{id}") {
        fun create(id: String) = "scrapers/$id"
    }
    object Launchers : Screen("launchers")
    object LauncherDetail : Screen("launchers/{id}") {
        fun create(id: String) = "launchers/$id"
    }
    object Settings : Screen("settings")
    object Backups : Screen("backups")
    object About : Screen("about")
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(Screen.Library.route) { inclusive = false }
        launchSingleTop = true
    }
}

@Composable
fun KoverNavHost() {
    val navController = rememberNavController()
    val library = hiltViewModel<LibraryHostViewModel>().repo
    val toTab: (com.ark.kover.ui.components.KoverTab) -> Unit = { tab ->
        when (tab) {
            com.ark.kover.ui.components.KoverTab.Library -> navController.switchTab(Screen.Library.route)
            com.ark.kover.ui.components.KoverTab.Scrapers -> navController.switchTab(Screen.Scrapers.route)
            com.ark.kover.ui.components.KoverTab.Launcher -> navController.switchTab(Screen.Launchers.route)
            com.ark.kover.ui.components.KoverTab.Backups -> navController.switchTab(Screen.Backups.route)
            com.ark.kover.ui.components.KoverTab.Settings -> navController.switchTab(Screen.Settings.route)
        }
    }
    val back: () -> Unit = { navController.popBackStack() }

    androidx.compose.runtime.CompositionLocalProvider(
        com.ark.kover.ui.components.LocalAboutAction provides {
            navController.navigate(Screen.About.route) { launchSingleTop = true }
        },
    ) {
    NavHost(
        navController = navController,
        startDestination = Screen.Library.route,
        enterTransition = { androidx.compose.animation.EnterTransition.None },
        exitTransition = { androidx.compose.animation.ExitTransition.None },
        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
        popExitTransition = { androidx.compose.animation.ExitTransition.None },
    ) {
        composable(Screen.Library.route) {
            LibraryScreen(
                onSystemClick = { system -> navController.navigate(Screen.RomList.create(system)) },
                onTabSelected = toTab,
            )
        }
        composable(Screen.RomList.route) { entry ->
            val system = entry.arguments?.getString("system").orEmpty()
            RomListScreen(
                system = system,
                onRomClick = { rom ->
                    library.selectedRom = rom
                    navController.navigate(Screen.RomCard.route)
                },
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.RomCard.route) {
            RomCardScreen(
                onEditMetadata = { navController.navigate(Screen.EditMetadata.route) },
                onEditAssets = { slot -> navController.navigate(Screen.EditAssets.create(slot)) },
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(
            route = Screen.EditAssets.route,
            arguments = listOf(navArgument("slot") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            EditAssetsScreen(
                initialSlot = entry.arguments?.getString("slot")?.takeIf { it.isNotBlank() },
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.EditMetadata.route) {
            EditMetadataScreen(
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.Scrapers.route) {
            ScraperListScreen(
                onScraperClick = { id -> navController.navigate(Screen.ScraperDetail.create(id)) },
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.ScraperDetail.route) { entry ->
            ScraperDetailScreen(
                scraperId = entry.arguments?.getString("id").orEmpty(),
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.Launchers.route) {
            LauncherListScreen(
                onLauncherClick = { id -> navController.navigate(Screen.LauncherDetail.create(id)) },
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.LauncherDetail.route) { entry ->
            LauncherDetailScreen(
                launcherId = entry.arguments?.getString("id").orEmpty(),
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.Settings.route) {
            GeneralSettingsScreen(
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.Backups.route) {
            BackupsScreen(
                onTabSelected = toTab,
                onBack = back,
            )
        }
        composable(Screen.About.route) {
            com.ark.kover.ui.settings.AboutScreen(
                onTabSelected = toTab,
                onBack = back,
            )
        }
    }
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class LibraryHostViewModel @javax.inject.Inject constructor(
    val repo: LibraryRepository,
) : androidx.lifecycle.ViewModel()
