// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import helium314.keyboard.UiFlowTest
import helium314.keyboard.initSettingsForTest
import helium314.keyboard.testContext
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.screens.ColorsScreen
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U-11: the theme name field lives in the app-bar title slot, where a TextField supportingText is
 * clipped, so the "invalid name" explanation never appeared. It is drawn below the bar now.
 */
@RunWith(AndroidJUnit4::class)
@UiFlowTest
class ColorsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val themeName = "uiFlowTestTheme"

    private val context: Context get() = testContext

    @Before
    fun setUp() {
        initSettingsForTest(context)
    }

    private fun showColorsScreen() {
        composeTestRule.setContent {
            Theme {
                Surface {
                    ColorsScreen(isNight = false, theme = themeName, onClickBack = {})
                }
            }
        }
    }

    @Test
    fun clearingTheThemeNameShowsTheInvalidNameMessageBelowTheAppBar() {
        showColorsScreen()
        val nameField = composeTestRule.onNodeWithText(themeName)
        // Capture the bar's extent before the field is emptied: the node no longer carries the
        // theme name afterwards, so it cannot be located by text a second time.
        val appBarBottom = nameField.fetchSemanticsNode().boundsInRoot.bottom

        // A blank name is rejected by KeyboardTheme.renameUserColors.
        nameField.performTextClearance()
        composeTestRule.waitForIdle()

        val message = composeTestRule.onNodeWithText(context.getString(R.string.name_invalid))
        message.assertIsDisplayed()
        val messageTop = message.fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "invalid-name message at $messageTop overlaps the app bar ending at $appBarBottom",
            messageTop >= appBarBottom
        )
    }
}
