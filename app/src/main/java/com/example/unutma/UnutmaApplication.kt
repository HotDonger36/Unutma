package com.example.unutma

import android.app.Application
import com.example.unutma.util.Lang

/** Runs once per app process, before any screen, widget or reminder, so they all start in the chosen language. */
class UnutmaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
    }
}
