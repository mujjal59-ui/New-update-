package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.AccessibilityStatusMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `app name is Riya AI and disabled accessibility never reports active`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Riya AI", appName)

    val health = AccessibilityStatusMonitor.refreshNow(context, speakIfTurnedOff = false)
    assertFalse(health.isAccessibilityTrulyActive)
  }
}
