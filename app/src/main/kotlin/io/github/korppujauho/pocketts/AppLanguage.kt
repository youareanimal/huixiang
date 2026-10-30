package io.github.korppujauho.pocketts

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language (Settings › UI), or the phone's. Android 13 and newer keep the choice themselves, so it's the
 * same one as in the phone's per-app language settings. Older ones get it here: kept in the settings, put on the app's
 * texts at start ([PocketApp]) and on each screen ([wrap]).
 */
object AppLanguage {
    /** The translations, each by its own name ("" is the phone's language). */
    val choices = listOf(
        "en" to "English", "fi" to "Suomi", "de" to "Deutsch", "es" to "Español",
        "fr" to "Français", "zh-Hans" to "简体中文", "ru" to "Русский",
    )

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The chosen language as one of [choices]' tags, or "" for the phone's. */
    fun current(context: Context): String {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            // Chosen in the phone's settings it can be "en-US" or a list: its first one's language is what counts
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',')
        } else {
            prefs(context).getString("language", "").orEmpty()
        }
        if (tag.isEmpty()) return ""
        val language = Locale.forLanguageTag(tag).language
        return choices.firstOrNull { Locale.forLanguageTag(it.first).language == language }?.first ?: tag
    }

    /** Switches the app to [tag] ("" for the phone's language); the screen is made again in it. */
    fun set(activity: Activity, tag: String) {
        if (tag == current(activity)) return
        AppLog.log("Language: ${tag.ifEmpty { "the phone's" }}")
        if (Build.VERSION.SDK_INT >= 33) {
            // Android makes the screen again itself
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString("language", tag).apply()
            apply(activity.applicationContext)
            activity.recreate()
        }
    }

    /** A screen's context in the chosen language (older Androids; newer ones do it themselves). */
    fun wrap(base: Context): Context {
        val locale = legacyLocale(base) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * Puts the chosen language on the app's own texts (notifications, messages from the connection), older Androids
     * only. Android puts the phone's back on any change of settings, so this runs again then ([PocketApp]).
     */
    @Suppress("DEPRECATION")
    fun apply(app: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val locale = legacyLocale(app) ?: Configuration(android.content.res.Resources.getSystem().configuration).locales[0]
        Locale.setDefault(locale)
        val res = app.resources
        val config = Configuration(res.configuration)
        if (config.locales[0] == locale) return
        config.setLocales(LocaleList(locale))
        res.updateConfiguration(config, res.displayMetrics)
    }

    private fun legacyLocale(context: Context): Locale? {
        if (Build.VERSION.SDK_INT >= 33) return null
        val tag = prefs(context).getString("language", "").orEmpty()
        return if (tag.isEmpty()) null else Locale.forLanguageTag(tag)
    }
}

/** The app itself: here only for the language on older Androids ([AppLanguage]). */
class PocketApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.apply(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.apply(this)
    }
}
