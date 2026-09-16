package com.bhickta.faceattendance.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

class KioskController(private val activity: Activity) {
    private val policyManager = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(activity, AttendanceDeviceAdminReceiver::class.java)

    val isDeviceOwner: Boolean
        get() = policyManager.isDeviceOwnerApp(activity.packageName)

    fun applyDedicatedDevicePolicy() {
        if (!isDeviceOwner) return

        policyManager.setLockTaskPackages(admin, arrayOf(activity.packageName))
        policyManager.setStatusBarDisabled(admin, true)
        policyManager.setKeyguardDisabled(admin, true)

        listOf(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
        ).forEach { restriction -> policyManager.addUserRestriction(admin, restriction) }
    }

    fun enterLockTaskIfAllowed() {
        if (isDeviceOwner && policyManager.isLockTaskPermitted(activity.packageName)) {
            activity.startLockTask()
        }
    }
}
