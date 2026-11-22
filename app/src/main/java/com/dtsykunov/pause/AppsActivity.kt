package com.dtsykunov.pause

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.dtsykunov.pause.databinding.ActivityAppsBinding
import kotlin.concurrent.thread

/**
 * The full list of launchable apps, searchable. Normally you choose which to pause; opened with
 * [EXTRA_ALT] it instead chooses the apps offered on the pause screen as alternatives.
 */
class AppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppsBinding
    private lateinit var adapter: AppListAdapter
    private var allEntries: List<AppEntry> = emptyList()
    private val altMode by lazy { intent.getBooleanExtra(EXTRA_ALT, false) }

    companion object {
        const val EXTRA_ALT = "alt_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        if (altMode) binding.toolbar.setTitle(R.string.section_alt_apps)

        adapter = AppListAdapter(emptyList()) { entry, on ->
            val current = (if (altMode) Prefs.altPackages(this) else Prefs.blockedPackages(this))
                .toMutableSet()
            if (on) {
                if (altMode && current.size >= AltApps.MAX) {
                    Toast.makeText(this, getString(R.string.alt_apps_limit, AltApps.MAX), Toast.LENGTH_SHORT).show()
                    return@AppListAdapter false
                }
                current.add(entry.packageName)
            } else {
                current.remove(entry.packageName)
            }
            if (altMode) Prefs.setAltPackages(this, current) else Prefs.setBlockedPackages(this, current)
            true
        }
        binding.appsRecycler.layoutManager = LinearLayoutManager(this)
        binding.appsRecycler.adapter = adapter

        binding.searchInput.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        loadApps()
    }

    private fun loadApps() {
        thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val blocked = if (altMode) Prefs.altPackages(this) else Prefs.blockedPackages(this)
            val entries = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .mapNotNull { pkg ->
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        AppEntry(
                            packageName = pkg,
                            label = pm.getApplicationLabel(info).toString(),
                            icon = pm.getApplicationIcon(info),
                            blocked = blocked.contains(pkg),
                        )
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allEntries = entries
                binding.loadingText.visibility = View.GONE
                applyFilter(binding.searchInput.text?.toString().orEmpty())
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) allEntries
        else allEntries.filter { it.label.contains(q, ignoreCase = true) }
        adapter.update(filtered)
        binding.emptyText.visibility =
            if (filtered.isEmpty() && allEntries.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
