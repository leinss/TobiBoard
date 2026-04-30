// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ReorderDialog
import helium314.keyboard.settings.screens.GetIcon
import androidx.core.content.edit

@Composable
fun ReorderSwitchPreference(setting: Setting, default: String, dialogDescription: String? = null) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Preference(
        name = setting.title,
        description = setting.description,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val items = parseReorderItems(prefs.getString(setting.key, default), default)
        ReorderDialog(
            onConfirmed = { reorderedItems ->
                val value = reorderedItems.joinToString(Separators.ENTRY) { it.name + Separators.KV + it.state }
                prefs.edit { putString(setting.key, value) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            onDismissRequest = { showDialog = false },
            onNeutral = { prefs.edit { remove(setting.key)} },
            neutralButtonText = if (prefs.contains(setting.key)) stringResource(R.string.button_default) else null,
            items = items,
            title = { Text(setting.title) },
            description = dialogDescription,
            displayItem = { item ->
                var checked by rememberSaveable { mutableStateOf(item.state) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeyboardIconsSet.instance.GetIcon(item.name)
                    val text = item.name.lowercase().getStringResourceOrName("", ctx)
                    val actualText = if (text != item.name.lowercase()) text
                        else item.name.lowercase().getStringResourceOrName("popup_keys_", ctx)
                    Text(actualText, Modifier.weight(1f))
                    Switch(
                        checked = checked,
                        onCheckedChange = { item.state = it; checked = it }
                    )
                }
            },
            getKey = { it.name }
        )
    }
}

private class KeyAndState(var name: String, var state: Boolean)

private fun parseReorderItems(value: String?, default: String): List<KeyAndState> {
    val defaultItems = parseReorderItems(default)
    if (value.isNullOrBlank()) return defaultItems

    val knownNames = defaultItems.mapTo(mutableSetOf()) { it.name }
    val result = parseReorderItems(value)
        .filter { it.name in knownNames }
        .distinctBy { it.name }
        .toMutableList()
    val presentNames = result.mapTo(mutableSetOf()) { it.name }
    defaultItems.filterTo(result) { it.name !in presentNames }
    return result.ifEmpty { defaultItems }
}

private fun parseReorderItems(value: String): List<KeyAndState> =
    value.split(Separators.ENTRY).mapNotNull { token ->
        val parts = token.split(Separators.KV)
        val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val state = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: return@mapNotNull null
        KeyAndState(name, state)
    }
