package com.example.locationservice

import android.app.Application

class LocationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        AppLogger.i("LocationApp", "Application onCreate (pid=${android.os.Process.myPid()})")
    }
}
