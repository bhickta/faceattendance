package com.bhickta.faceattendance

import android.app.Application
import com.bhickta.faceattendance.sync.SyncScheduler

class FaceAttendanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.install(this)
    }
}
