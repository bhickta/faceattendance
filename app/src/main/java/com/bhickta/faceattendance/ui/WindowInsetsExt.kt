package com.bhickta.faceattendance.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Enables edge-to-edge drawing and pads [target] so its content is not hidden
 * behind the navigation bar (and optionally the keyboard on form screens).
 */
fun AppCompatActivity.applyCompatInsets(
    target: View,
    applyTop: Boolean = false,
    applyBottom: Boolean = true,
    includeIme: Boolean = false,
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val baseTop = target.paddingTop
    val baseBottom = target.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
        val types = if (includeIme) {
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        } else {
            WindowInsetsCompat.Type.systemBars()
        }
        val bars = insets.getInsets(types)
        view.updatePadding(
            top = if (applyTop) baseTop + bars.top else baseTop,
            bottom = if (applyBottom) baseBottom + bars.bottom else baseBottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(target)
}
