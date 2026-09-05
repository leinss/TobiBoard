// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import helium314.keyboard.UiFlowTest
import helium314.keyboard.initSettingsForTest
import helium314.keyboard.testContext
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.screens.ClipboardManagementScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U-10: with no clipboard history the screen used to be a title bar and nothing else, which reads
 * as a failed load rather than an empty list.
 */
@RunWith(AndroidJUnit4::class)
@UiFlowTest
class ClipboardManagementScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = testContext

    @Before
    fun setUp() {
        initSettingsForTest(context)
        // The clipboard database survives between runs, so the empty state is only reachable if the
        // test empties it first.
        ClipboardDao.getInstance(context)?.clear()
    }

    @Test
    fun theClipboardScreenExplainsItselfWhenThereIsNoHistory() {
        composeTestRule.setContent {
            Theme {
                Surface {
                    ClipboardManagementScreen(onClickBack = {})
                }
            }
        }
        composeTestRule.onNodeWithText(context.getString(R.string.clipboard_history_empty_hint))
            .assertIsDisplayed()
    }
}
