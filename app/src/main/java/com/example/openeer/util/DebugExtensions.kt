package com.example.openeer.util

import android.content.Context
import android.content.pm.ApplicationInfo

fun Context.isDebugBuild(): Boolean {
    val appInfo = applicationContext.applicationInfo
    return (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
