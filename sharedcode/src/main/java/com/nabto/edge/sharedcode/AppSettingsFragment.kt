package com.nabto.edge.sharedcode

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AppSettingsFragment : PreferenceFragmentCompat() {
    private val repo: NabtoRepository by inject()
    private val database: DeviceDatabase by inject()
    private val manager: NabtoConnectionManager by inject()

    private val displayNameKey = internalConfig.DISPLAY_NAME_PREF
    private val resetDatabaseKey = "preferences_reset_database"
    private val resetPrivateKeyKey = "preferences_reset_private_key"

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        val getDrawable = { drawable: Int -> AppCompatResources.getDrawable(context, drawable) }

        val prefs = listOf(
            EditTextPreference(context).apply {
                key = displayNameKey
                title = "Display Name"
                icon = getDrawable(R.drawable.ic_person)
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                dialogTitle = "Display Name"
                dialogMessage = "This is the default name that will be used for pairing with a device."
            },

            ConfirmDialogPreference(context).apply {
                key = resetDatabaseKey
                title = "Reset Bookmarked Devices"
                icon = getDrawable(R.drawable.ic_list_remove)
                summary = "Clear the list of paired devices on Home"
                dialogTitle = "Warning"
                dialogMessage = getString(R.string.app_settings_database_reset_dialog)
                onDialogClosed = { confirmed ->
                    if (confirmed) {
                        repo.getApplicationScope().launch {
                            database.deviceDao().deleteAll()
                        }
                        view?.snack(getString(R.string.app_settings_database_reset_snack), Snackbar.LENGTH_SHORT)
                    }
                }
            },

            ConfirmDialogPreference(context).apply {
                key = resetPrivateKeyKey
                title = "Reset Private Key"
                icon = getDrawable(R.drawable.ic_key_reset)
                summary = "Reset and get a new private key"
                dialogTitle = "Warning"
                dialogMessage = getString(R.string.app_settings_reset_client_pk_dialog)
                onDialogClosed = { confirmed ->
                    if (confirmed) {
                        repo.resetClientPrivateKey()
                        manager.releaseAll()
                        view?.snack(getString(R.string.app_settings_client_pk_snack), Snackbar.LENGTH_SHORT);
                    }
                }
            }
        )
        prefs.forEach { pref -> screen.addPreference(pref) }
        preferenceScreen = screen
    }

}