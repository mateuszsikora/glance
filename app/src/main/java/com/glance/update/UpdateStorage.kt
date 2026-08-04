package com.glance.update

import android.content.Context
import java.io.File

/**
 * Location of the staged APK.
 *
 * The file lives in the app's private cache: PackageInstaller reads it through our own process, so
 * it never needs to be world-readable or exposed through a FileProvider.
 */
internal object UpdateStorage {

    fun stagedApk(context: Context): File {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        return File(directory, APK_NAME)
    }

    fun clearStagedApk(context: Context) {
        stagedApk(context).delete()
    }

    private const val DIRECTORY = "updates"
    private const val APK_NAME = "glance-update.apk"
}
