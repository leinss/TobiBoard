// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import helium314.keyboard.ImeShell
import helium314.keyboard.UiFlowTest
import helium314.keyboard.initSettingsForTest
import helium314.keyboard.testContext
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.AiProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the welcome wizard as the user sees it: which step is shown, how the numbered step row
 * reads, and which control moves the flow forward.
 *
 * The wizard derives its step from live system state, so these tests enable and select the IME
 * first. Without that the wizard sits on the "enable the keyboard" step and the AI sub-flow, where
 * both audited defects live, is unreachable.
 */
@RunWith(AndroidJUnit4::class)
@UiFlowTest
class WelcomeWizardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var previousIme: String

    private val context: Context get() = testContext

    @Before
    fun setUp() {
        // The wizard reads Settings.getValues() indirectly through the screens it can open, and
        // SettingsActivity.onCreate is what normally populates it. Mirror that here.
        initSettingsForTest(context)
        // The visible step count depends on the provider branch, so pin it rather than inheriting
        // whatever a previous test or a manual run left behind.
        context.prefs().edit { putString(Settings.PREF_AI_PROVIDER, AiProvider.LOCAL.prefValue) }
        previousIme = ImeShell.enableAndSelect()
    }

    @After
    fun tearDown() {
        ImeShell.restore(previousIme)
    }

    private fun showWizard() {
        composeTestRule.setContent {
            Theme {
                Surface {
                    WelcomeWizard(close = {}, finish = {})
                }
            }
        }
    }

    /** Text of every step circle, in the order they are drawn. */
    private fun indicatorLabels(): List<String> =
        composeTestRule.onAllNodesWithTag(WIZARD_STEP_INDICATOR_TAG).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    /** Index of the single selected step circle, or -1 when none or several are selected. */
    private fun selectedIndicatorIndex(): Int {
        val selected = composeTestRule.onAllNodesWithTag(WIZARD_STEP_INDICATOR_TAG).fetchSemanticsNodes()
            .mapIndexedNotNull { index, node ->
                index.takeIf { node.config.getOrElseNullable(SemanticsProperties.Selected) { false } == true }
            }
        return if (selected.size == 1) selected.single() else -1
    }

    @Test
    fun theWizardShowsTheProviderStepOnceTheImeIsEnabledAndSelected() {
        showWizard()
        composeTestRule.onNodeWithText(context.getString(R.string.setup_ai_provider_choice_title))
            .assertExists()
    }

    @Test
    fun theStepIndicatorNumbersTheVisibleStepsConsecutivelyFromOne() {
        showWizard()
        val labels = indicatorLabels()
        val expected = wizardVisibleSteps(providerIsCloud = false).indices.map { (it + 1).toString() }
        // U-6: the row used to number the fixed 0..8 step ids, so it skipped the step the chosen
        // provider does not use and jumped from 6 to 8.
        assertEquals(expected, labels)
    }

    @Test
    fun theStepIndicatorMarksExactlyOneStepAsCurrent() {
        showWizard()
        val providerPosition = wizardVisibleSteps(providerIsCloud = false).indexOf(WizardStepResolver.PROVIDER)
        assertEquals(providerPosition, selectedIndicatorIndex())
    }

    @Test
    fun theProviderStepPutsContinueOnTheFilledPrimaryControl() {
        showWizard()
        // U-19: the provider picker used to be the filled button, which read as the forward action
        // even though it only opens a list.
        composeTestRule.onNodeWithTag(WIZARD_PRIMARY_ACTION_TAG)
            .assertTextContains(context.getString(R.string.setup_continue_action))
        composeTestRule.onNodeWithTag(WIZARD_SECONDARY_ACTION_TAG)
            .assertTextContains(providerName(), substring = true)
    }

    @Test
    fun theProviderStepNamesTheProviderOnlyOnTheButtonThatChangesIt() {
        showWizard()
        // U-19: a separate "current provider" line above the button said the same thing twice.
        // Matching on the whole "Provider: X" line rather than on the bare name, because the
        // trade-off paragraph legitimately mentions on-device and cloud in prose.
        val providerLine = context.getString(R.string.setup_ai_provider_select, providerName())
        val nodes = composeTestRule.onAllNodesWithText(providerLine, substring = true)
            .fetchSemanticsNodes()
        assertEquals(1, nodes.size)
    }

    @Test
    fun tappingContinueOnTheProviderStepAdvancesTheIndicator() {
        showWizard()
        val steps = wizardVisibleSteps(providerIsCloud = false)
        val before = selectedIndicatorIndex()
        assertEquals(steps.indexOf(WizardStepResolver.PROVIDER), before)

        composeTestRule.onNodeWithTag(WIZARD_PRIMARY_ACTION_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(before + 1, selectedIndicatorIndex())
        // The numbering must stay 1..N after the branch is taken, not gain or lose a circle.
        assertEquals(steps.indices.map { (it + 1).toString() }, indicatorLabels())
    }

    private fun providerName(): String = when (
        AiProvider.fromPref(context.prefs().getString(Settings.PREF_AI_PROVIDER, AiProvider.LOCAL.prefValue))
    ) {
        AiProvider.OPENROUTER -> context.getString(R.string.ai_provider_openrouter)
        AiProvider.PAYPERQ -> context.getString(R.string.ai_provider_payperq)
        AiProvider.LOCAL -> context.getString(R.string.ai_provider_local)
    }
}
