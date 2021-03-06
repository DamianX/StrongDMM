package strongdmm.ui.panel.preferences

import strongdmm.event.EventHandler
import strongdmm.event.type.Reaction
import strongdmm.event.type.service.TriggerPreferencesService
import strongdmm.service.preferences.prefs.PreferenceBoolean
import strongdmm.service.preferences.prefs.PreferenceEnum

class ViewController(
    private val state: State
) : EventHandler {
    fun doSelectOption(mode: PreferenceEnum, pref: PreferenceEnum) {
        pref.getValue().data = mode.getValue().data
        savePreferences()
    }

    fun doToggleOption(pref: PreferenceBoolean) {
        pref.getValue().data = !pref.getValue().data
        savePreferences()
    }

    fun savePreferences() {
        sendEvent(TriggerPreferencesService.SavePreferences())
    }

    fun blockApplication() {
        sendEvent(Reaction.ApplicationBlockChanged(true))
    }

    fun checkOpenStatus() {
        if (state.checkOpenStatus && !state.isOpened.get()) {
            state.checkOpenStatus = false
            sendEvent(Reaction.ApplicationBlockChanged(false))
        }
    }
}
