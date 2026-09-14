// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the toolbar preference defaults against a newly added [ToolbarKey]. A key missing from a
 * default pref string is invisible in Settings, Toolbar, so the user cannot add it to the toolbar
 * at all. TEXT_FIX was added exactly for that reason.
 */
class ToolbarPrefsTest {

    private fun namesIn(pref: String) = pref.split(Separators.ENTRY)
        .filter { it.isNotEmpty() }
        .map { it.substringBefore(Separators.KV) }

    @Test fun defaultToolbarPrefListsEveryKeyExceptCloseHistory() {
        val names = namesIn(defaultToolbarPref)
        assertEquals(names.size, names.toSet().size, "duplicate entry in defaultToolbarPref")
        val expected = ToolbarKey.entries.map { it.name }.toSet() - ToolbarKey.CLOSE_HISTORY.name
        assertEquals(expected, names.toSet())
    }

    @Test fun defaultPinnedToolbarPrefListsEveryKeyExceptCloseHistory() {
        val names = namesIn(defaultPinnedToolbarPref)
        val expected = ToolbarKey.entries.map { it.name }.toSet() - ToolbarKey.CLOSE_HISTORY.name
        assertEquals(expected, names.toSet())
    }

    @Test fun defaultClipboardToolbarPrefListsEveryKey() {
        val names = namesIn(defaultClipboardToolbarPref)
        assertEquals(ToolbarKey.entries.map { it.name }.toSet(), names.toSet())
    }

    @Test fun textFixIsOffByDefaultButOfferable() {
        val entry = defaultToolbarPref.split(Separators.ENTRY)
            .single { it.substringBefore(Separators.KV) == ToolbarKey.TEXT_FIX.name }
        assertTrue(entry.endsWith("${Separators.KV}false"), "TEXT_FIX must not be enabled by default: $entry")
    }

    @Test fun theTextFixToolbarKeyRunsBothPrompts() {
        // Tap is the primary prompt, long-press the second one, so one toolbar slot reaches both.
        assertEquals(KeyCode.TEXT_FIX, defaultCodeForToolbarKey(ToolbarKey.TEXT_FIX))
        assertEquals(KeyCode.TEXT_FIX_2, defaultLongClickCodeForToolbarKey(ToolbarKey.TEXT_FIX))
    }

    @Test fun everyToolbarKeyHasATapCode() {
        // A key with no code is a toolbar button that does nothing when tapped.
        ToolbarKey.entries.forEach {
            assertTrue(defaultCodeForToolbarKey(it) != KeyCode.UNSPECIFIED, "$it has no tap code")
        }
    }
}
