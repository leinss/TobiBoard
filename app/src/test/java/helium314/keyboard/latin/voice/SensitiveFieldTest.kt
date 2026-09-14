// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predicate text fix and voice input share. Voice used to have no field guard at all and
 * recorded inside password fields, so these cases are the contract that keeps the two aligned.
 */
class SensitiveFieldTest {
    private fun call(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        isPasswordField: Boolean = false,
        noLearning: Boolean = false,
        incognitoModeEnabled: Boolean = false,
        imeOptions: Int = 0,
    ): Boolean = SensitiveField.isSensitive(
        inputType = inputType,
        isPasswordField = isPasswordField,
        noLearning = noLearning,
        incognitoModeEnabled = incognitoModeEnabled,
        imeOptions = imeOptions,
    )

    @Test
    fun plainTextFieldIsNotSensitive() {
        assertFalse(call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))
    }

    @Test
    fun passwordFieldIsSensitive() {
        assertTrue(call(isPasswordField = true))
    }

    @Test
    fun textPasswordVariationIsSensitive() {
        assertTrue(
            call(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                isPasswordField = true,
            )
        )
    }

    @Test
    fun noLearningFieldIsSensitive() {
        assertTrue(call(noLearning = true))
    }

    @Test
    fun incognitoModeIsSensitive() {
        assertTrue(call(incognitoModeEnabled = true))
    }

    @Test
    fun imeFlagNoPersonalizedLearningIsSensitive() {
        // The only signal below API 26, where InputAttributes.mNoLearning is hardcoded false.
        assertTrue(call(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    @Test
    fun numericPinIsSensitive() {
        assertTrue(call(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun plainNumberFieldIsNotSensitive() {
        // Unsupported for text fix, but not sensitive — the two answers are separate.
        assertFalse(call(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL))
    }

    @Test
    fun noSuggestionsFlagIsNotSensitive() {
        // Set by React Native / Flutter for autocomplete reasons, not privacy ones.
        assertFalse(call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun emailFieldIsNotSensitive() {
        assertFalse(call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }
}
