/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import net.newpipe.app.screen.about.AboutScreen

/**
 * Navigation display for compose screens
 * @param startDestination Starting destination for the app
 * @param onCloseRequest Callback to close app when there are no more screens left in backstack
 */
@Composable
fun NavDisplay(startDestination: Screen, onCloseRequest: () -> Unit) {
    val backstack = rememberNavBackStack(screenConfig, startDestination)

    fun onNavigateUp() = if (backstack.size > 1) backstack.removeLastOrNull() else onCloseRequest()

    NavDisplay(
        backStack = backstack,
        entryProvider = entryProvider {
            entry<Screen.About> {
                AboutScreen(
                    onNavigateUp = ::onNavigateUp
                )
            }
        }
    )
}
