/*
* SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
* SPDX-License-Identifier: GPL-3.0-or-later
*/

package net.newpipe.app.screen.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewWrapper
import net.newpipe.app.composable.PreferenceRow
import net.newpipe.app.composable.TopAppBar
import net.newpipe.app.navigation.Navigator
import net.newpipe.app.preview.ThemePreviewProvider
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.content
import newpipe.shared.generated.resources.ic_bug_report
import newpipe.shared.generated.resources.ic_cloud_download
import newpipe.shared.generated.resources.ic_file_download
import newpipe.shared.generated.resources.ic_headset
import newpipe.shared.generated.resources.ic_history
import newpipe.shared.generated.resources.ic_language
import newpipe.shared.generated.resources.ic_notifications
import newpipe.shared.generated.resources.ic_palette
import newpipe.shared.generated.resources.ic_search
import newpipe.shared.generated.resources.ic_settings_backup_restore
import newpipe.shared.generated.resources.notifications
import newpipe.shared.generated.resources.search
import newpipe.shared.generated.resources.settings
import newpipe.shared.generated.resources.settings_category_appearance_title
import newpipe.shared.generated.resources.settings_category_backup_restore_title
import newpipe.shared.generated.resources.settings_category_debug_title
import newpipe.shared.generated.resources.settings_category_downloads_title
import newpipe.shared.generated.resources.settings_category_history_title
import newpipe.shared.generated.resources.settings_category_updates_title
import newpipe.shared.generated.resources.settings_category_video_audio_title
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

data class SettingsCategory(
    val title: StringResource,
    val icon: DrawableResource,
    // TODO: Replace with a Destination once sub-screens are migrated
    val onClick: () -> Unit = {}
)

@Composable
fun SettingsHomeScreen(navigator: Navigator = koinInject()) {
    SettingsHomeScreenContent(
        onNavigateUp = { navigator.navigateUp() }
    )
}

@Composable
fun SettingsHomeScreenContent(
    categories: List<SettingsCategory> = defaultCategories(),
    onNavigateUp: () -> Unit = {},
    onSearchClick: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(Res.string.settings),
                onNavigateUp = onNavigateUp,
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_search),
                            contentDescription = stringResource(Res.string.search)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {
            items(items = categories) { category ->
                PreferenceRow(
                    title = stringResource(category.title),
                    icon = painterResource(category.icon),
                    onClick = category.onClick
                )
            }
        }
    }
}

@Composable
private fun defaultCategories(): List<SettingsCategory> = listOf(
    SettingsCategory(Res.string.settings_category_video_audio_title, Res.drawable.ic_headset),
    SettingsCategory(Res.string.settings_category_downloads_title, Res.drawable.ic_file_download),
    SettingsCategory(Res.string.settings_category_appearance_title, Res.drawable.ic_palette),
    SettingsCategory(Res.string.settings_category_history_title, Res.drawable.ic_history),
    SettingsCategory(Res.string.content, Res.drawable.ic_language),
    SettingsCategory(Res.string.notifications, Res.drawable.ic_notifications),
    SettingsCategory(Res.string.settings_category_updates_title, Res.drawable.ic_cloud_download),
    SettingsCategory(Res.string.settings_category_backup_restore_title, Res.drawable.ic_settings_backup_restore),
    SettingsCategory(Res.string.settings_category_debug_title, Res.drawable.ic_bug_report)
)

@PreviewWrapper(ThemePreviewProvider::class)
@PreviewLightDark
@Composable
private fun SettingsHomeScreenPreview() {
    SettingsHomeScreenContent()
}
