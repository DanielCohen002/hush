package com.djcohen.hush

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Asks GitHub for the latest Hush release. Only ever runs when the user taps "Check for
 * updates": there is no background or scheduled network use.
 */
object UpdateChecker {
    private const val LATEST = "https://api.github.com/repos/DanielCohen002/hush/releases/latest"

    sealed class Result {
        data class UpToDate(val current: String) : Result()
        data class Available(val current: String, val latest: String, val url: String) : Result()
        object Failed : Result()
    }

    fun currentVersion(c: Context): String =
        try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }

    /** Runs the check off the main thread; [onResult] is called back on it. */
    fun check(c: Context, onResult: (Result) -> Unit) {
        val current = currentVersion(c)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        thread {
            val result = try {
                val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                try {
                    if (conn.responseCode != 200) {
                        Result.Failed
                    } else {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val tag = json.getString("tag_name")
                        val assets = json.optJSONArray("assets")
                        val apk = assets?.let { a ->
                            (0 until a.length()).map { a.getJSONObject(it) }
                                .firstOrNull { it.getString("name").endsWith(".apk") }
                                ?.getString("browser_download_url")
                        }
                        val url = apk ?: json.getString("html_url")
                        if (isNewer(tag, current)) Result.Available(current, tag.removePrefix("v"), url)
                        else Result.UpToDate(current)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                Result.Failed
            }
            main.post { onResult(result) }
        }
    }

    /** True when [latest] (e.g. "v0.6.5") is a higher version than [current] ("0.6.4-debug"). */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
