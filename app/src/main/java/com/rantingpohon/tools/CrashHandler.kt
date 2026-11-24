package com.rantingpohon.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    private const val PREF_NAME = "ranting_crash_log"
    private const val KEY_LAST_CRASH = "last_crash_trace"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val report = "=== CRASH REPORT ($time) ===\nThread: ${thread.name}\n\n$sw"

                appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, report)
                    .commit()
            } catch (_: Exception) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun showPendingCrashDialog(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val trace = prefs.getString(KEY_LAST_CRASH, null) ?: return

        prefs.edit().remove(KEY_LAST_CRASH).apply()

        AlertDialog.Builder(context)
            .setTitle("Rekaman Error Terakhir")
            .setMessage(trace)
            .setPositiveButton("Copy Log") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CrashLog", trace))
                Toast.makeText(context, "Log error disalin ke clipboard!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }
}
