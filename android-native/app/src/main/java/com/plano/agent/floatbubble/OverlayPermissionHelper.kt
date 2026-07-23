package com.plano.agent.floatbubble

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.plano.agent.R

object OverlayPermissionHelper {
    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestIfNeeded(activity: FragmentActivity, onGranted: () -> Unit = {}) {
        if (hasPermission(activity)) {
            onGranted()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.allow) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
