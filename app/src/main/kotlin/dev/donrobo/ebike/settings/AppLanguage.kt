package dev.donrobo.ebike.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * In-app language (S3). Stored by the system's per-app language setting (API 33+) or by AppCompat's
 * locale store (API 31/32, `autoStoreLocales` in the manifest), not in [Settings], so the system
 * settings page and the app always agree. Switching recreates the activities at runtime.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null), GERMAN("de"), ENGLISH("en");

    companion object {
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales().get(0)?.language ?: return SYSTEM
            return entries.firstOrNull { it.tag == tag } ?: SYSTEM
        }

        fun set(lang: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(
                lang.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList(),
            )
        }
    }
}
