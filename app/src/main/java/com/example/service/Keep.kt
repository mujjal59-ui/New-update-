package com.example.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/** Accessibility ও ব্যাকগ্রাউন্ড সচল রাখার সাহায্যকারী ফাংশন */
object Keep {
  private fun comp(c: Context) = ComponentName(c, RiyaAccessibilityService::class.java)

  private fun same(s: String, c: Context) =
    s.equals(comp(c).flattenToString(), true) || s.equals(comp(c).flattenToShortString(), true)

  /** Live check: active service singleton OR OS-verified Accessibility snapshot. */
  fun accessibilityOn(c: Context): Boolean {
    if (RiyaAccessibilityService.instance != null) {
      RiyaRuntimeBridge.setServiceConnected(true)
      return true
    }
    return AccessibilityStatusMonitor.refreshNow(c, speakIfTurnedOff = false)
      .isAccessibilityTrulyActive
  }

  /** Checks Settings.Secure / AccessibilityManager directly so background guard stays alive across transitions. */
  fun isEnabledInSettingsOrAm(c: Context): Boolean {
    if (RiyaAccessibilityService.instance != null) return true
    return AccessibilityStatusMonitor.isEnabledInOsSettings(c)
  }

  fun canSelfEnable(c: Context): Boolean =
    c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
      PackageManager.PERMISSION_GRANTED

  @Volatile private var lastRebindMs = 0L

  /** WRITE_SECURE_SETTINGS দেওয়া থাকলে Accessibility নিজেই চালু (বা আবার চালু) করে নেয় */
  fun ensureAccessibility(c: Context): Boolean {
    if (!canSelfEnable(c)) return false
    return try {
      val cr = c.contentResolver
      val parts =
        (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
          .split(':')
          .filter { it.isNotBlank() }
      if (parts.none { same(it, c) }) {
        Settings.Secure.putString(
          cr,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
          (parts + comp(c).flattenToString()).joinToString(":"),
        )
      }
      if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  /** Settings-এ অন থাকা সত্ত্বেও সিস্টেম বাইন্ডিং আটকে গেলে স্বয়ংক্রিয় রিবাইন্ড করে */
  fun forceRebindAccessibility(c: Context): Boolean {
    if (!canSelfEnable(c)) return false
    val now = System.currentTimeMillis()
    if (now - lastRebindMs < 15_000L) return false
    lastRebindMs = now
    return try {
      val cr = c.contentResolver
      val parts =
        (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
          .split(':')
          .filter { it.isNotBlank() && !same(it, c) }
      Settings.Secure.putString(
        cr,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        parts.joinToString(":"),
      )
      Settings.Secure.putString(
        cr,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        (parts + comp(c).flattenToString()).joinToString(":"),
      )
      Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun start(c: Context, i: Intent): Boolean =
    try {
      c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (_: Exception) {
      false
    }

  fun openAppInfo(c: Context) {
    start(
      c,
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")),
    )
  }

  /** সরাসরি LXR--AI-এর Accessibility পেজ খোলার চেষ্টা, না হলে সাধারণ Accessibility সেটিংস */
  fun openAccessibility(c: Context) {
    val key = comp(c).flattenToString()
    val i =
      Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", key)
        .putExtra(
          ":settings:show_fragment_args",
          Bundle().apply { putString(":settings:fragment_args_key", key) },
        )
    if (!start(c, i)) openAppInfo(c)
  }

  fun openNotificationAccess(c: Context) {
    if (!start(c, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
      openAppInfo(c)
    }
  }

  fun openBattery(c: Context) {
    if (
      start(
        c,
        Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:${c.packageName}"),
        ),
      )
    )
      return
    if (start(c, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
    openAppInfo(c)
  }

  /** Infinix (XOS) / Xiaomi / Oppo / Realme / Vivo / Samsung / OnePlus Autostart পেজ খোলার চেষ্টা */
  fun openOemAutostart(c: Context) {
    val list =
      listOf(
        "com.transsion.phonemaster" to
          "com.cyin.himgr.autostart.AutoStartActivity", // Infinix Phone Master
        "com.transsion.phonemaster" to "com.cyin.himgr.MainActivity",
        "com.miui.securitycenter" to
          "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to
          "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to
          "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to
          "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.huawei.systemmanager" to
          "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.oneplus.security" to
          "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
      )
    for ((p, cl) in list) if (start(c, Intent().setComponent(ComponentName(p, cl)))) return
    openAppInfo(c)
  }
}
