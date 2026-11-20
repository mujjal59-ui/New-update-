package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.riyaDataStore: DataStore<Preferences> by preferencesDataStore(name = "riya_prefs")

data class RiyaSettings(
  val voiceOutputEnabled: Boolean = true,
  val speechRate: Float = 0.94f,
  val strictAutoReplyMaster: Boolean = true,
  val requireBossPermissionBeforeSend: Boolean = true,
  val whatsAppAutoReply: Boolean = true,
  val imoAutoReply: Boolean = true,
  val messengerAutoReply: Boolean = true,
  val customReplyTemplate: String = RiyaPreferences.DEFAULT_BUSY_REPLY_TEMPLATE_BN,
  val enforceFullScreenRead: Boolean = true,
  val fastExecutionMode: Boolean = true,
  val autoAnnounceAccessibilityLoss: Boolean = true,
)

class RiyaPreferences(private val context: Context) {
  companion object {
    const val DEFAULT_BUSY_REPLY_TEMPLATE_BN =
      "আসসালামু আলাইকুম। আশা করি ভালো আছেন।\n\n" +
        "আমি LXR, বস এই মুহূর্তে কিছুটা ব্যস্ত রয়েছেন। তিনি অবসর হলেই আপনার সঙ্গে যত দ্রুত সম্ভব যোগাযোগ করবেন, ইনশাআল্লাহ❤️📢"

    private val LEGACY_DEFAULT_TEMPLATES =
      setOf(
        "আসসালামু আলাইকুম। আশা করি ভালো আছেন।\n\n" +
          "আমি LXR--AI,বস এই মুহূর্তে কিছুটা ব্যস্ত রয়েছেন। তিনি অবসর হলেই আপনার সঙ্গে যত দ্রুত সম্ভব যোগাযোগ করবেন, ইনশাআল্লাহ।\n\n" +
          "ততক্ষণ পর্যন্ত আপনার সদয় অপেক্ষার জন্য কৃতজ্ঞতা প্রকাশ করছি ধন্যবাদ।",
        "হ্যালো, বস এই মুহূর্তে ব্যস্ত আছেন। আমি LXR--AI, কিছুক্ষণ পর বস আপনার সাথে যোগাযোগ করবেন।",
        "হ্যালো, বস একটু ব্যস্ত আছেন। আমি LXR--AI, কিছুক্ষণ পর বস রিপ্লাই দেবেন।",
        "আমি একটু ব্যস্ত আছি বস, পরে কথা বলছি।",
      )

    fun normalizeTemplate(stored: String?): String {
      val trimmed = stored?.trim().orEmpty()
      if (trimmed.isEmpty() || trimmed in LEGACY_DEFAULT_TEMPLATES) {
        return DEFAULT_BUSY_REPLY_TEMPLATE_BN
      }
      return trimmed
    }
  }

  private object Keys {
    val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
    val SPEECH_RATE = floatPreferencesKey("speech_rate")
    val STRICT_MASTER = booleanPreferencesKey("strict_auto_reply_master")
    val REQUIRE_PERMISSION = booleanPreferencesKey("require_boss_permission")
    val WHATSAPP_ENABLED = booleanPreferencesKey("whatsapp_auto_reply")
    val IMO_ENABLED = booleanPreferencesKey("imo_auto_reply")
    val MESSENGER_ENABLED = booleanPreferencesKey("messenger_auto_reply")
    val CUSTOM_TEMPLATE = stringPreferencesKey("custom_reply_template")
    val ENFORCE_SCREEN_READ = booleanPreferencesKey("enforce_screen_read")
    val FAST_EXECUTION = booleanPreferencesKey("fast_execution")
    val ANNOUNCE_A11Y_LOSS = booleanPreferencesKey("announce_a11y_loss")
  }

  val settingsFlow: Flow<RiyaSettings> =
    context.riyaDataStore.data.map { prefs ->
      RiyaSettings(
        voiceOutputEnabled = prefs[Keys.VOICE_ENABLED] ?: true,
        speechRate = prefs[Keys.SPEECH_RATE] ?: 0.94f,
        strictAutoReplyMaster = prefs[Keys.STRICT_MASTER] ?: true,
        requireBossPermissionBeforeSend = prefs[Keys.REQUIRE_PERMISSION] ?: true,
        whatsAppAutoReply = prefs[Keys.WHATSAPP_ENABLED] ?: true,
        imoAutoReply = prefs[Keys.IMO_ENABLED] ?: true,
        messengerAutoReply = prefs[Keys.MESSENGER_ENABLED] ?: true,
        customReplyTemplate = normalizeTemplate(prefs[Keys.CUSTOM_TEMPLATE]),
        enforceFullScreenRead = prefs[Keys.ENFORCE_SCREEN_READ] ?: true,
        fastExecutionMode = prefs[Keys.FAST_EXECUTION] ?: true,
        autoAnnounceAccessibilityLoss = prefs[Keys.ANNOUNCE_A11Y_LOSS] ?: true,
      )
    }

  suspend fun currentSettings(): RiyaSettings = settingsFlow.first()

  suspend fun updateVoiceEnabled(enabled: Boolean) {
    context.riyaDataStore.edit { it[Keys.VOICE_ENABLED] = enabled }
  }

  suspend fun updateSpeechRate(rate: Float) {
    context.riyaDataStore.edit { it[Keys.SPEECH_RATE] = rate.coerceIn(0.70f, 1.30f) }
  }

  suspend fun updateStrictMaster(enabled: Boolean) {
    context.riyaDataStore.edit { it[Keys.STRICT_MASTER] = enabled }
  }

  suspend fun updateRequireBossPermission(required: Boolean) {
    context.riyaDataStore.edit { it[Keys.REQUIRE_PERMISSION] = required }
  }

  suspend fun updateAppAutoReply(appPackage: String, enabled: Boolean) {
    context.riyaDataStore.edit { prefs ->
      when {
        appPackage.contains("whatsapp") -> prefs[Keys.WHATSAPP_ENABLED] = enabled
        appPackage.contains("imo") -> prefs[Keys.IMO_ENABLED] = enabled
        appPackage.contains("facebook") -> prefs[Keys.MESSENGER_ENABLED] = enabled
      }
    }
  }

  suspend fun updateCustomTemplate(template: String) {
    val cleaned = template.trim()
    if (cleaned.isNotEmpty()) {
      context.riyaDataStore.edit { it[Keys.CUSTOM_TEMPLATE] = cleaned }
    }
  }

  suspend fun updateEnforceScreenRead(enforce: Boolean) {
    context.riyaDataStore.edit { it[Keys.ENFORCE_SCREEN_READ] = enforce }
  }

  suspend fun updateFastExecution(fast: Boolean) {
    context.riyaDataStore.edit { it[Keys.FAST_EXECUTION] = fast }
  }
}
