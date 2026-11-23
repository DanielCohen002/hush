package com.djcohen.hush

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.djcohen.hush.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Prefs.theme(this).styleRes)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.masterRow.setOnClickListener {
            if (!isServiceEnabled()) return@setOnClickListener
            val on = !binding.masterSwitch.isChecked
            binding.masterSwitch.isChecked = on
            Prefs.setGlobalEnabled(this, on)
            updateMasterSubtitle(on)
        }

        binding.accessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.accessHelp.setOnClickListener {
            showHint(R.string.access_help_title, R.string.access_help_body)
        }

        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.batteryHelp.setOnClickListener { showBatteryHelp() }

        binding.statsButton.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        binding.pausedAppsRow.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        binding.altAppsRow.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_ALT, true))
        }

        binding.breathStyleRow.setOnClickListener {
            val styles = BreathStyle.values()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.section_breath_style)
                .setSingleChoiceItems(
                    styles.map { getString(it.labelRes) }.toTypedArray(),
                    styles.indexOf(Prefs.breathStyle(this)),
                ) { dialog, which ->
                    Prefs.setBreathStyle(this, styles[which])
                    binding.breathStyleValue.setText(styles[which].labelRes)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.puzzleTypeRow.setOnClickListener {
            val types = PuzzleType.selectable
            val chosen = Prefs.puzzleTypes(this).toMutableSet()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.section_puzzle_type)
                .setMultiChoiceItems(
                    types.map { getString(it.labelRes) }.toTypedArray(),
                    BooleanArray(types.size) { types[it] in chosen },
                ) { _, which, on ->
                    if (on) chosen.add(types[which]) else chosen.remove(types[which])
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Prefs.setPuzzleTypes(this, chosen)
                    refreshPuzzleRows()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.puzzleLevelRow.setOnClickListener {
            val levels = PuzzleLevel.values()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.section_puzzle_level)
                .setSingleChoiceItems(
                    levels.map { getString(it.labelRes) }.toTypedArray(),
                    levels.indexOf(Prefs.puzzleLevel(this)),
                ) { dialog, which ->
                    Prefs.setPuzzleLevel(this, levels[which])
                    binding.puzzleLevelValue.setText(levels[which].labelRes)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.updateRow.setOnClickListener {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
            UpdateChecker.check(this) { result ->
                if (isFinishing || isDestroyed) return@check
                val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.check_updates)
                when (result) {
                    is UpdateChecker.Result.UpToDate -> dialog
                        .setMessage(getString(R.string.update_up_to_date, result.current))
                        .setPositiveButton(R.string.got_it, null)
                    is UpdateChecker.Result.Available -> dialog
                        .setMessage(getString(R.string.update_available, result.current, result.latest))
                        .setPositiveButton(R.string.update_download) { _, _ ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                            } catch (_: Exception) {
                            }
                        }
                        .setNegativeButton(R.string.update_later, null)
                    UpdateChecker.Result.Failed -> dialog
                        .setMessage(R.string.update_failed)
                        .setPositiveButton(R.string.got_it, null)
                }
                dialog.show()
            }
        }

        binding.puzzleLibraryRow.setOnClickListener {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.section_puzzle_library)
                .setMessage(R.string.library_body)
                .setPositiveButton(R.string.library_download) { _, _ ->
                    Toast.makeText(this, R.string.library_downloading, Toast.LENGTH_SHORT).show()
                    PuzzleUpdater.download(this) { total ->
                        if (isFinishing || isDestroyed) return@download
                        Toast.makeText(
                            this,
                            if (total == null) getString(R.string.library_failed)
                            else getString(R.string.library_done, total),
                            Toast.LENGTH_LONG,
                        ).show()
                        refreshLibraryRow()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
            if (PuzzleUpdater.hasDownloaded(this)) {
                dialog.setNeutralButton(R.string.library_reset) { _, _ ->
                    PuzzleUpdater.reset(this)
                    refreshLibraryRow()
                }
            }
            dialog.show()
        }

        binding.themeRow.setOnClickListener {
            val themes = AppTheme.values()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.section_theme)
                .setSingleChoiceItems(
                    themes.map { getString(it.labelRes) }.toTypedArray(),
                    themes.indexOf(Prefs.theme(this)),
                ) { dialog, which ->
                    dialog.dismiss()
                    if (themes[which] != Prefs.theme(this)) {
                        Prefs.setTheme(this, themes[which])
                        recreate()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.lengthHelp.setOnClickListener {
            showHint(R.string.section_length, R.string.help_length_body)
        }
        binding.allowHelp.setOnClickListener {
            showHint(R.string.section_allow, R.string.help_allow_body)
        }

        val initial = Prefs.pauseSeconds(this)
        binding.durationSlider.value = initial.toFloat()
        binding.durationLabel.text = getString(R.string.duration_value, initial)
        binding.durationSlider.addOnChangeListener { _, value, _ ->
            val seconds = value.toInt()
            binding.durationLabel.text = getString(R.string.duration_value, seconds)
            Prefs.setPauseSeconds(this, seconds)
        }

        val rangeMin = Prefs.durationMin(this)
        val rangeMax = Prefs.durationMax(this)
        binding.durationRangeSlider.values = listOf(rangeMin.toFloat(), rangeMax.toFloat())
        binding.rangeLabel.text = getString(R.string.duration_range_value, rangeMin, rangeMax)
        binding.durationRangeSlider.addOnChangeListener { slider, _, _ ->
            val sorted = slider.values.sorted()
            val lo = sorted.first().toInt()
            val hi = sorted.last().toInt()
            binding.rangeLabel.text = getString(R.string.duration_range_value, lo, hi)
            Prefs.setDurationRange(this, lo, hi)
        }

        binding.randomizeSwitch.isChecked = Prefs.durationMode(this) == Prefs.MODE_RANDOM
        applyDurationModeVisibility(binding.randomizeSwitch.isChecked)
        binding.randomizeRow.setOnClickListener {
            val random = !binding.randomizeSwitch.isChecked
            binding.randomizeSwitch.isChecked = random
            Prefs.setDurationMode(this, if (random) Prefs.MODE_RANDOM else Prefs.MODE_FIXED)
            applyDurationModeVisibility(random)
        }

        binding.showTimerSwitch.isChecked = Prefs.showTimer(this)
        binding.showTimerRow.setOnClickListener {
            val show = !binding.showTimerSwitch.isChecked
            binding.showTimerSwitch.isChecked = show
            Prefs.setShowTimer(this, show)
        }

        binding.phraseInput.setText(Prefs.phrase(this))
        binding.saveMessageButton.setOnClickListener {
            Prefs.setPhrase(this, binding.phraseInput.text?.toString().orEmpty())
            binding.phraseInput.clearFocus()
            hideKeyboard()
            Toast.makeText(this, R.string.message_saved, Toast.LENGTH_SHORT).show()
        }

        val allow = Prefs.allowMinutes(this)
        binding.allowSlider.value = allow.toFloat()
        binding.allowLabel.text = getString(R.string.allow_value, allow)
        binding.allowSlider.addOnChangeListener { _, value, _ ->
            val minutes = value.toInt()
            binding.allowLabel.text = getString(R.string.allow_value, minutes)
            Prefs.setAllowMinutes(this, minutes)
        }

        refreshHoursRows()
        binding.hoursRow.setOnClickListener {
            val on = !binding.hoursSwitch.isChecked
            Prefs.setHoursEnabled(this, on)
            refreshHoursRows()
        }
        binding.hoursRangeRow.setOnClickListener {
            pickTime(R.string.hours_pick_start, Prefs.hoursStart(this)) { start ->
                pickTime(R.string.hours_pick_end, Prefs.hoursEnd(this)) { end ->
                    Prefs.setHours(this, start, end)
                    refreshHoursRows()
                }
            }
        }

        binding.pauseAgainSwitch.isChecked = Prefs.pauseAgainWhenAllowEnds(this)
        binding.pauseAgainRow.setOnClickListener {
            val on = !binding.pauseAgainSwitch.isChecked
            binding.pauseAgainSwitch.isChecked = on
            Prefs.setPauseAgainWhenAllowEnds(this, on)
        }
    }

    /** Summary of the puzzle toggles; the difficulty and library rows only matter with a puzzle on. */
    private fun refreshPuzzleRows() {
        val on = PuzzleType.selectable.filter { it in Prefs.puzzleTypes(this) }
        binding.puzzleTypeValue.text =
            if (on.isEmpty()) getString(R.string.puzzle_none)
            else on.joinToString(", ") { getString(it.labelRes) }
        val v = if (on.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.puzzleLevelRow.visibility = v
        binding.puzzleLibraryRow.visibility = v
    }

    private fun fmtMinutes(m: Int): String =
        android.text.format.DateFormat.getTimeFormat(this).format(
            java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, m / 60); set(java.util.Calendar.MINUTE, m % 60) }.time
        )

    private fun refreshHoursRows() {
        val on = Prefs.hoursEnabled(this)
        binding.hoursSwitch.isChecked = on
        binding.hoursRangeRow.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        binding.hoursRangeValue.text =
            getString(R.string.hours_range, fmtMinutes(Prefs.hoursStart(this)), fmtMinutes(Prefs.hoursEnd(this)))
    }

    private fun pickTime(titleRes: Int, minutes: Int, onPicked: (Int) -> Unit) {
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(
                if (android.text.format.DateFormat.is24HourFormat(this)) com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                else com.google.android.material.timepicker.TimeFormat.CLOCK_12H
            )
            .setHour(minutes / 60).setMinute(minutes % 60)
            .setTitleText(titleRes)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "hours")
    }

    private fun refreshLibraryRow() {
        binding.puzzleLibraryValue.setText(
            if (PuzzleUpdater.hasDownloaded(this)) R.string.library_downloaded else R.string.library_builtin
        )
    }

    private fun applyDurationModeVisibility(random: Boolean) {
        binding.durationLabel.visibility = if (random) View.GONE else View.VISIBLE
        binding.durationSlider.visibility = if (random) View.GONE else View.VISIBLE
        binding.rangeLabel.visibility = if (random) View.VISIBLE else View.GONE
        binding.durationRangeSlider.visibility = if (random) View.VISIBLE else View.GONE
    }

    private fun showHint(titleRes: Int, bodyRes: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.phraseInput.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        if (!isServiceEnabled()) enableServiceIfPermitted()
        val enabled = isServiceEnabled()

        binding.masterRow.isEnabled = enabled
        binding.masterSwitch.isEnabled = enabled
        val on = enabled && Prefs.globalEnabled(this)
        binding.masterSwitch.isChecked = on
        binding.masterTitle.alpha = if (enabled) 1f else 0.5f
        binding.masterSubtitle.text = getString(
            if (!enabled) R.string.master_needs_access else if (on) R.string.master_on else R.string.master_off
        )

        binding.statusText.text =
            getString(if (enabled) R.string.status_on else R.string.status_off)
        binding.accessButton.visibility = if (enabled) View.GONE else View.VISIBLE
        applyStatusCard(binding.accessCard, binding.statusText, enabled)

        // Background-kill mitigation: if Hush isn't battery-optimization exempt the OS can kill
        // the accessibility service and it silently stops pausing apps until re-toggled.
        val exempt = isIgnoringBatteryOptimizations()
        binding.batteryStatusText.text =
            getString(if (exempt) R.string.battery_on else R.string.battery_off)
        binding.batteryButton.visibility = if (exempt) View.GONE else View.VISIBLE
        applyStatusCard(binding.batteryCard, binding.batteryStatusText, exempt)

        val count = Prefs.blockedPackages(this).size
        binding.pausedAppsCount.text = resources.getQuantityString(R.plurals.paused_count, count, count)

        val altCount = Prefs.altPackages(this).size
        binding.altAppsCount.text =
            if (altCount == 0) getString(R.string.alt_apps_none)
            else resources.getQuantityString(R.plurals.alt_apps_count, altCount, altCount)
        refreshPuzzleRows()
        binding.puzzleLevelValue.setText(Prefs.puzzleLevel(this).labelRes)
        refreshLibraryRow()
        binding.themeValue.setText(Prefs.theme(this).labelRes)
        binding.updateValue.text = getString(R.string.version_value, UpdateChecker.currentVersion(this))
        binding.breathStyleValue.setText(Prefs.breathStyle(this).labelRes)
    }

    /** Tint a status card green when the thing it tracks is ready, rose when it needs attention. */
    private fun applyStatusCard(card: MaterialCardView, label: TextView, ok: Boolean) {
        card.setCardBackgroundColor(
            getColor(if (ok) R.color.status_ok_container else R.color.status_warn_container)
        )
        card.setStrokeColor(
            getColor(if (ok) R.color.status_ok_outline else R.color.status_warn_outline)
        )
        label.setTextColor(getColor(if (ok) R.color.status_ok else R.color.status_warn))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** Ask the OS to stop battery-optimizing Hush. The direct dialog needs the
     *  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission; fall back to the settings list otherwise. */
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                openAppSettings()
            }
        }
    }

    /** Manufacturer-specific steps for the OEM "autostart / never sleep" settings that the
     *  battery exemption alone doesn't cover. No AOSP API exists for these, so we guide by text. */
    private fun showBatteryHelp() {
        val body = when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> R.string.battery_help_samsung
            "xiaomi", "redmi", "poco" -> R.string.battery_help_xiaomi
            "oppo", "realme", "oneplus" -> R.string.battery_help_oppo
            "vivo", "iqoo" -> R.string.battery_help_vivo
            "huawei", "honor" -> R.string.battery_help_huawei
            else -> R.string.battery_help_generic
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_help_title)
            .setMessage(body)
            .setPositiveButton(R.string.got_it, null)
            .setNeutralButton(R.string.battery_open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.battery_more_help) { _, _ -> openDontKillMyApp() }
            .show()
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    private fun openDontKillMyApp() {
        val slug = Build.MANUFACTURER.lowercase().replace(' ', '-')
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$slug")))
        } catch (_: Exception) {
        }
    }

    private fun updateMasterSubtitle(on: Boolean) {
        binding.masterSubtitle.text = getString(if (on) R.string.master_on else R.string.master_off)
    }

    /** With WRITE_SECURE_SETTINGS (granted once over adb) Hush can turn its own accessibility
     *  service on, so no trip into Settings is needed. Without it this quietly does nothing. */
    private fun enableServiceIfPermitted() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val me = ComponentName(this, AppMonitorService::class.java).flattenToString()
            val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val next = if (cur.isNullOrEmpty()) me else "$cur:$me"
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (_: Exception) {
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, AppMonitorService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
