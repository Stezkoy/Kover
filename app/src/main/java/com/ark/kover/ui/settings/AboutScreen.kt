package com.ark.kover.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ark.kover.BuildConfig
import com.ark.kover.R
import com.ark.kover.ui.components.ActionRow
import com.ark.kover.ui.components.AppScaffold
import com.ark.kover.ui.components.KoverTab
import com.ark.kover.ui.components.SectionTitle
import com.ark.kover.ui.theme.Dimens

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
fun AboutScreen(
    onTabSelected: (KoverTab) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    AppScaffold(
        selectedTab = KoverTab.Settings,
        aboutSelected = true,
        onTabSelected = onTabSelected,
        breadcrumbs = listOf(stringResource(R.string.nav_settings), stringResource(R.string.about_title)),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(
                R.string.about_build,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle(text = stringResource(R.string.about_launchers), modifier = Modifier.padding(top = Dimens.Gap6))
        LauncherInfo("ES-DE", R.string.about_launcher_esde)
        LauncherInfo("NeoStation", R.string.about_launcher_neostation)
        LauncherInfo("Cocoon", R.string.about_launcher_cocoon)

        SectionTitle(text = stringResource(R.string.about_services))
        ServiceRow("ES-DE", R.string.about_esde_desc, "https://es-de.org", context)
        ServiceRow("NeoAssets", R.string.about_neoassets_desc, "https://neoassets.dev", context)
        ServiceRow("SteamGridDB", R.string.about_sgdb_desc, "https://www.steamgriddb.com", context)
        ServiceRow("libretro-thumbnails", R.string.about_libretro_desc, "https://github.com/libretro-thumbnails", context)
        ServiceRow("Steam Store", R.string.about_steam_desc, "https://store.steampowered.com", context)
        ServiceRow("ScreenScraper", R.string.about_screenscraper_desc, "https://www.screenscraper.fr", context)
        ServiceRow("MyMemory", R.string.about_mymemory_desc, "https://mymemory.translated.net", context)
        ServiceRow("GitHub", R.string.about_github_desc, "https://github.com/Stezkoy/Kover", context)

        SectionTitle(text = stringResource(R.string.about_credits), modifier = Modifier.padding(top = Dimens.Gap6))
        Text(
            text = stringResource(R.string.about_icons),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_system_art),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_license),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow(
            title = stringResource(R.string.about_third_party),
            subtitle = "THIRD_PARTY_NOTICES.md",
            onClick = {
                openUrl(context, "https://github.com/Stezkoy/Kover/blob/main/THIRD_PARTY_NOTICES.md")
            },
        )

        SectionTitle(text = stringResource(R.string.about_support), modifier = Modifier.padding(top = Dimens.Gap6))
        ActionRow(
            title = "Boosty",
            subtitle = stringResource(R.string.about_boosty_desc),
            onClick = { openUrl(context, "https://boosty.to/stezkoy") },
        )
        ActionRow(
            title = stringResource(R.string.about_source),
            subtitle = "github.com/Stezkoy/Kover",
            onClick = { openUrl(context, "https://github.com/Stezkoy/Kover") },
        )
    }
}

@Composable
private fun LauncherInfo(name: String, descriptionRes: Int) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Gap4, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceRow(name: String, descriptionRes: Int, url: String, context: Context) {
    ActionRow(
        title = name,
        subtitle = stringResource(descriptionRes),
        trailing = Uri.parse(url).host,
        onClick = { openUrl(context, url) },
    )
}
