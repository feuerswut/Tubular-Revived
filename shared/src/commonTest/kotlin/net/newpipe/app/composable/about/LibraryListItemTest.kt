/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.composable.about

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue
import net.newpipe.app.model.Library
import net.newpipe.app.model.License

@OptIn(ExperimentalTestApi::class)
class LibraryListItemTest {

    @Test
    fun testLibraryListItem() = runComposeUiTest {
        val library = Library(
            id = "Test id",
            name = "Test name",
            developer = "Test developer",
            license = License(
                type = License.Companion.TYPE.APACHE_2_0,
                text = ""
            ),
            website = "https://www.example.com"
        )
        var clicked = false
        setContent {
            LibraryListItem(
                library = library,
                onClick = {
                    clicked = true
                }
            )
        }

        onNodeWithText(library.name).assertIsDisplayed()
        onNodeWithText("By ${library.developer} under ${library.license.spdxId}").apply {
            assertIsDisplayed()
            performClick()
            assertTrue(clicked)
        }
    }
}
