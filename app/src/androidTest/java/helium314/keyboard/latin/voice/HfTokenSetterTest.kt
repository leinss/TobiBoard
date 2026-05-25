// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import helium314.keyboard.latin.voice.local.HfAuth
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test-runner-as-setup-script: writes the HF access token supplied via
 * `-e hf_token <value>` into the app's encrypted SecretStore. Used to seed the
 * device before running [LocalLiteRtEngineTest] when the active IME (TobiBoard
 * itself) blocks adb-driven text entry in the in-app token dialog.
 */
@RunWith(AndroidJUnit4::class)
class HfTokenSetterTest {

    @Test fun setsAccessToken() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("hf_token")
            ?: error("Pass the token via -e hf_token <hf_...>")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        HfAuth.setToken(ctx, token)
        val stored = HfAuth.currentToken(ctx)
        assertNotNull("token did not round-trip through SecretStore", stored)
        Log.i("HfTokenSetterTest", "stored token length=${stored?.length}")
    }
}
