/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app

import androidx.compose.runtime.Composable
import net.newpipe.app.di.KoinApp
import net.newpipe.app.navigation.Screen
import net.newpipe.app.theme.AppTheme
import org.koin.compose.KoinApplication
import org.koin.plugin.module.dsl.koinConfiguration

/**
 * Entry point for the multiplatform compose application
 * @param startDestination Starting destination for the app
 */
@Composable
fun App(startDestination: Screen? = null) {
    KoinApplication(configuration = koinConfiguration<KoinApp>()) {
        AppTheme {
        }
    }
}
