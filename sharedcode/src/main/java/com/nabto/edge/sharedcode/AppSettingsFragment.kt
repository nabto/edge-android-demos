package com.nabto.edge.sharedcode

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class AppSettingsFragment : PreferenceFragmentCompat() {
    private val displayNameKey = internalConfig.DISPLAY_NAME_PREF
    private val resetDatabaseKey = "preferences_reset_database"
    private val resetPrivateKeyKey = "preferences_reset_private_key"

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val prefs = listOf(
            EditTextPreference(context).apply {
                key = displayNameKey
                title = "Display Name"
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                dialogTitle = "Display Name"
                dialogMessage = "This is the default name that will be used for pairing with a device."
            },

            Preference(context).apply {
                key = resetDatabaseKey
                title = "Reset Bookmarked Devices"
                summary = "Clear the list of paired devices on Home"
            },

            Preference(context).apply {
                key = resetPrivateKeyKey
                title = "Reset Private Key"
                summary = "Reset and get a new private key"
            }
        )
        prefs.forEach { pref -> screen.addPreference(pref) }
        preferenceScreen = screen
    }

}