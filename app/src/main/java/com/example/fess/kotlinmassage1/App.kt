package com.example.fess.kotlinmassage1

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Application-класс: включает Material You (динамические цвета из обоев)
 * на Android 12+ (API 31+). На остальных версиях остаётся палитра темы.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Применяет dynamic colors ко всем активити, создаваемым после вызова.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
