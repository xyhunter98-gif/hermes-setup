package com.hermes.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Finds an installed, launchable app by spoken name ("Hermes open Spotify")
 * and starts it. Matches on the app's visible label, not its package id,
 * since that's what a person actually says.
 */
object AppLauncher {

    fun launch(context: Context, spokenName: String): Boolean {
        val pm = context.packageManager
        val needle = spokenName.trim().lowercase()
        if (needle.isEmpty()) return false

        val launchableApps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )

        val match = launchableApps.firstOrNull {
            it.loadLabel(pm).toString().lowercase() == needle
        } ?: launchableApps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(needle)
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }
}
