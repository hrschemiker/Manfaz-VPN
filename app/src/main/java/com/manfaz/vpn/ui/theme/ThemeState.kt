package com.manfaz.vpn.ui.theme

import android.content.Context
import com.manfaz.vpn.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide theme selection, so the switcher applies immediately across the UI. */
object ThemeState {

    data class Appearance(val mode: ThemeMode, val dynamicColor: Boolean)

    private val _appearance = MutableStateFlow(Appearance(ThemeMode.SYSTEM, false))
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun load(context: Context) {
        val p = Prefs(context)
        _appearance.value = Appearance(parse(p.themeMode), p.dynamicColor)
    }

    fun setMode(context: Context, mode: ThemeMode) {
        Prefs(context).themeMode = mode.name
        _appearance.value = _appearance.value.copy(mode = mode)
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        Prefs(context).dynamicColor = enabled
        _appearance.value = _appearance.value.copy(dynamicColor = enabled)
    }

    private fun parse(name: String): ThemeMode =
        runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
}
