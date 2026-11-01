package cad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class AppearanceMode(val label: String) { LIGHT("Hell"), DARK("Dunkel") }
internal object AppAppearance {
    private val preferences by lazy { java.util.prefs.Preferences.userRoot().node("local/kotlincad/appearance") }
    private var migrate=preferences.get("mode","SYSTEM")=="SYSTEM"
    var mode by mutableStateOf(runCatching { AppearanceMode.valueOf(preferences.get("mode","LIGHT")) }.getOrDefault(AppearanceMode.LIGHT))
        private set
    val dark get()=mode==AppearanceMode.DARK
    fun migrateSystem(dark: Boolean) { if(migrate) { migrate=false;select(if(dark) AppearanceMode.DARK else AppearanceMode.LIGHT) } }
    fun preview(value: AppearanceMode) { migrate=false;mode=value }
    fun select(value: AppearanceMode) { migrate=false;mode=value;runCatching { preferences.put("mode",value.name);preferences.flush() } }
}
