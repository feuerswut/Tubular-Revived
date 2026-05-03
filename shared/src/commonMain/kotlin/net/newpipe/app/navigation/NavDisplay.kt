/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay

/**
 * Navigation display for compose screens
 * @param startDestination Starting destination for the app
 */
@Composable
fun NavDisplay(startDestination: Screen) {
    val backstack = rememberNavBackStack(screenConfig, startDestination)

    NavDisplay(
        backStack = backstack,
        entryProvider = entryProvider {
        }
    )
}
