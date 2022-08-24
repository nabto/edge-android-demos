package com.nabto.edge.nabtoheatpumpdemo

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AppSettingsConfirmationDialogFragment(
    private val msg: String,
    private val onConfirm: () -> Unit
    ) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext())
            .setMessage(msg)
            .setPositiveButton(getString(R.string.confirm)) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .create()

    companion object {
        const val TAG = "AppSettingsConfirmationDialog"
    }
}

class AppSettingsFragment : Fragment() {
    private val repo: NabtoRepository by inject()
    private val database: DeviceDatabase by inject()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_app_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pkTextView = view.findViewById<TextView>(R.id.app_settings_client_pk)
        pkTextView.text = repo.getClientPrivateKey()

        val etDisplayName = view.findViewById<EditText>(R.id.app_settings_et_displayname)
        repo.getDisplayName().value?.let {
            etDisplayName.setText(it)
        }

        val saveButton = view.findViewById<Button>(R.id.app_settings_save_display_name)
        saveButton.setOnClickListener {
            if (etDisplayName.length() == 0) {
                etDisplayName.error = "Display name cannot be empty"
                return@setOnClickListener
            }

            repo.setDisplayName(etDisplayName.text.toString())
            view.snack(getString(R.string.app_settings_saved_snack), Snackbar.LENGTH_SHORT)
            clearFocusAndHideKeyboard()
        }

        val resetDatabaseButton = view.findViewById<Button>(R.id.app_settings_reset_database)
        resetDatabaseButton.setOnClickListener {
            val msg = getString(R.string.app_settings_database_reset_dialog)
            val onConfirm = {
                repo.getApplicationScope().launch(Dispatchers.IO) {
                    database.deviceDao().deleteAll()
                }
                view.snack(getString(R.string.app_settings_database_reset_snack), Snackbar.LENGTH_SHORT)
                Unit
            }
            AppSettingsConfirmationDialogFragment(msg, onConfirm).show(childFragmentManager, AppSettingsConfirmationDialogFragment.TAG)
        }

        val resetPrivateKeyButton = view.findViewById<Button>(R.id.app_settings_reset_client_pk)
        resetPrivateKeyButton.setOnClickListener {
            val msg = getString(R.string.app_settings_reset_client_pk_dialog)
            val onConfirm = {
                repo.resetClientPrivateKey()
                pkTextView.text = repo.getClientPrivateKey()
                view.snack(getString(R.string.app_settings_client_pk_snack), Snackbar.LENGTH_SHORT)
                Unit
            }
            AppSettingsConfirmationDialogFragment(msg, onConfirm).show(childFragmentManager, AppSettingsConfirmationDialogFragment.TAG)
        }
    }
}