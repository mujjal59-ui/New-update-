package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DeviceBrandProfile
import com.example.data.ScreenLockType
import com.example.data.Store
import com.example.service.LockVaultManager
import kotlin.math.hypot

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LockVaultPage(back: () -> Unit, onNavigate: (String) -> Unit) {
  val ctx = LocalContext.current
  val detectedBrand = remember { LockVaultManager.detectDeviceBrand() }

  var selectedBrand by remember { mutableStateOf(Store.deviceBrandProfile(ctx)) }
  var phoneLockType by remember { mutableStateOf(Store.phoneLockType(ctx)) }
  var phonePin by remember { mutableStateOf(Store.phonePin(ctx)) }
  var showPhonePin by remember { mutableStateOf(false) }
  var phonePattern by remember { mutableStateOf(Store.phonePattern(ctx)) }

  var appSameAsPhone by remember { mutableStateOf(Store.appLockSameAsPhone(ctx)) }
  var appLockType by remember { mutableStateOf(Store.appLockType(ctx)) }
  var appPin by remember { mutableStateOf(Store.appPin(ctx)) }
  var showAppPin by remember { mutableStateOf(false) }
  var appPattern by remember { mutableStateOf(Store.appPattern(ctx)) }

  var autoUnlockEnabled by remember { mutableStateOf(Store.autoUnlockEnabled(ctx)) }

  val fieldColors =
    OutlinedTextFieldDefaults.colors(
      focusedTextColor = Color.White,
      unfocusedTextColor = Color.White,
      focusedBorderColor = CYBER_CYAN,
      unfocusedBorderColor = Color(0xFF475073),
      focusedLabelColor = CYBER_CYAN,
      unfocusedLabelColor = Color(0xFFAAB4D0),
    )

  fun validateBeforeSave(): String? {
    if (phoneLockType == ScreenLockType.PATTERN && phonePattern.length in 1..3) {
      return "ফোনের প্যাটার্ন লকে কমপক্ষে ৪টি বিন্দু যুক্ত করুন বস।"
    }
    if (!appSameAsPhone && appLockType == ScreenLockType.PATTERN && appPattern.length in 1..3) {
      return "অ্যাপ লকের প্যাটার্নে কমপক্ষে ৪টি বিন্দু যুক্ত করুন বস।"
    }
    return null
  }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(FULL_BG_GRADIENT)
        .statusBarsPadding()
        .navigationBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageTopBar(
      title = "ফোন ও অ্যাপ লক ভল্ট",
      icon = Icons.Filled.Security,
      iconColors = listOf(CYBER_RED, CYBER_AMBER),
      onBack = back,
      onNavigate = onNavigate,
    )

    // Device Brand selector
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(1.dp, CYBER_CYAN.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.PhoneAndroid, "Device Brand", listOf(CYBER_CYAN, CYBER_BLUE), sizeDp = 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text("ডিভাইস ব্র্যান্ড প্রোফাইল", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          Text(
            "শনাক্তকৃত ফোন: ${detectedBrand.labelBn}",
            color = CYBER_CYAN,
            fontSize = 11.5.sp,
          )
        }
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        DeviceBrandProfile.entries.forEach { brand ->
          FilterChip(
            selected = selectedBrand == brand,
            onClick = { selectedBrand = brand },
            modifier = Modifier.testTag("brand_chip_${brand.name.lowercase()}"),
            label = { Text(brand.labelBn, fontSize = 11.5.sp) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = CYBER_BLUE,
                selectedLabelColor = Color.White,
                labelColor = Color(0xFFB8C4E0),
              ),
          )
        }
      }
    }

    // Phone Screen Lock Card
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .testTag("phone_lock_card")
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(1.2.dp, CYBER_RED.copy(alpha = 0.48f), RoundedCornerShape(18.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.Lock, "Phone Lock", listOf(CYBER_RED, CYBER_PURPLE), sizeDp = 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text("স্মার্টফোন স্ক্রিন লক (Phone Lock)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
          Text("লক থাকা অবস্থায় বসের ডাকে অটো-আনলক করে কাজ শেষে পুনরায় লক করবে", color = Color(0xFFAAB4D0), fontSize = 11.sp)
        }
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        ScreenLockType.entries.forEach { type ->
          FilterChip(
            selected = phoneLockType == type,
            onClick = { phoneLockType = type },
            modifier = Modifier.testTag("phone_lock_type_${type.name.lowercase()}"),
            label = { Text(type.labelBn, fontSize = 11.5.sp) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = CYBER_RED,
                selectedLabelColor = Color.White,
                labelColor = Color(0xFFB8C4E0),
              ),
          )
        }
      }

      when (phoneLockType) {
        ScreenLockType.PIN -> {
          OutlinedTextField(
            value = phonePin,
            onValueChange = { if (it.length <= 8) phonePin = it },
            modifier = Modifier.fillMaxWidth().testTag("phone_pin_input"),
            label = { Text("ফোন PIN / পাসওয়ার্ড (সর্বোচ্চ ৮ সংখ্যা/অক্ষর)") },
            singleLine = true,
            colors = fieldColors,
            visualTransformation = if (showPhonePin) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              TextButton({ showPhonePin = !showPhonePin }) {
                Text(if (showPhonePin) "লুকান" else "দেখুন", color = CYBER_CYAN, fontSize = 11.5.sp)
              }
            },
          )
          Text(
            "অক্ষর/সংখ্যা: ${phonePin.length}/8",
            color = Color(0xFFAAB4D0),
            fontSize = 11.sp,
          )
        }
        ScreenLockType.PATTERN -> {
          PatternLockPad(
            patternDigits = phonePattern,
            onPatternChanged = { phonePattern = it },
            modifier = Modifier.testTag("phone_pattern_pad"),
          )
        }
        ScreenLockType.NONE -> {
          Text("স্ক্রিন লক সংরক্ষিত নেই — কেবল সোয়াইপ লক থাকলে তা স্বয়ংক্রিয়ভাবে খুলবে।", color = Color(0xFFAAB4D0), fontSize = 11.5.sp)
        }
      }
    }

    // App Lock Card
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .testTag("app_lock_card")
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(1.2.dp, CYBER_PURPLE.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.AppShortcut, "App Lock", listOf(CYBER_PURPLE, CYBER_BLUE), sizeDp = 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text("অ্যাপ লক (App Lock — Infinix / Redmi / Realme)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          Text("WhatsApp, imo বা Messenger অ্যাপে লক থাকলে তা নিজেই আনলক করবে", color = Color(0xFFAAB4D0), fontSize = 11.sp)
        }
      }

      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E152E))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "অ্যাপ লকে ফোনের লকই ব্যবহার করো",
          color = Color.White,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f),
        )
        Switch(
          checked = appSameAsPhone,
          onCheckedChange = { appSameAsPhone = it },
          modifier = Modifier.testTag("app_lock_same_switch"),
          colors = SwitchDefaults.colors(checkedTrackColor = CYBER_PURPLE, checkedThumbColor = Color.White),
        )
      }

      if (!appSameAsPhone) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          ScreenLockType.entries.forEach { type ->
            FilterChip(
              selected = appLockType == type,
              onClick = { appLockType = type },
              modifier = Modifier.testTag("app_lock_type_${type.name.lowercase()}"),
              label = { Text(type.labelBn, fontSize = 11.5.sp) },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = CYBER_PURPLE,
                  selectedLabelColor = Color.White,
                  labelColor = Color(0xFFB8C4E0),
                ),
            )
          }
        }
        when (appLockType) {
          ScreenLockType.PIN -> {
            OutlinedTextField(
              value = appPin,
              onValueChange = { if (it.length <= 8) appPin = it },
              modifier = Modifier.fillMaxWidth().testTag("app_pin_input"),
              label = { Text("অ্যাপ লক PIN / পাসওয়ার্ড (সর্বোচ্চ ৮ সংখ্যা)") },
              singleLine = true,
              colors = fieldColors,
              visualTransformation = if (showAppPin) VisualTransformation.None else PasswordVisualTransformation(),
              trailingIcon = {
                TextButton({ showAppPin = !showAppPin }) {
                  Text(if (showAppPin) "লুকান" else "দেখুন", color = CYBER_CYAN, fontSize = 11.5.sp)
                }
              },
            )
          }
          ScreenLockType.PATTERN -> {
            PatternLockPad(
              patternDigits = appPattern,
              onPatternChanged = { appPattern = it },
              modifier = Modifier.testTag("app_pattern_pad"),
            )
          }
          ScreenLockType.NONE -> {
            Text("আলাদা অ্যাপ লক নেই।", color = Color(0xFFAAB4D0), fontSize = 11.5.sp)
          }
        }
      }
    }

    // Conditional Auto-Unlock & Safety Guarantee Card
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(CYBER_CARD, RoundedCornerShape(18.dp))
          .border(1.2.dp, CYBER_GREEN.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PremiumIconBadge(Icons.Filled.LockOpen, "Auto Unlock", listOf(CYBER_GREEN, CYBER_CYAN), sizeDp = 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text("স্বয়ংক্রিয় আনলক ও নিরাপদ রি-লক", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          Text(
            "ফোন লক থাকলে কাজ শেষে পুনরায় লক করে বসকে জানাবে",
            color = CYBER_GREEN,
            fontSize = 11.5.sp,
          )
        }
        Switch(
          checked = autoUnlockEnabled,
          onCheckedChange = { autoUnlockEnabled = it },
          modifier = Modifier.testTag("auto_unlock_switch"),
          colors = SwitchDefaults.colors(checkedTrackColor = CYBER_GREEN, checkedThumbColor = Color.White),
        )
      }
      Text(
        "🔒 নিরাপত্তা নিশ্চয়তা: ফোন আগে থেকেই আনলক অবস্থায় থাকলে বসের স্পষ্ট অনুমতি ছাড়া LXR--AI কখনোই স্ক্রিন লক করবে না।",
        color = Color(0xFFD5DEF5),
        fontSize = 11.5.sp,
      )
    }

    Button(
      onClick = {
        val err = validateBeforeSave()
        if (err != null) {
          Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
          return@Button
        }
        Store.setDeviceBrandProfile(ctx, selectedBrand)
        Store.setPhoneLockType(ctx, phoneLockType)
        Store.setPhonePin(ctx, phonePin)
        Store.setPhonePattern(ctx, phonePattern)
        Store.setAppLockSameAsPhone(ctx, appSameAsPhone)
        Store.setAppLockType(ctx, appLockType)
        Store.setAppPin(ctx, appPin)
        Store.setAppPattern(ctx, appPattern)
        Store.setAutoUnlockEnabled(ctx, autoUnlockEnabled)
        Toast.makeText(ctx, "হয়েছে, বস! ফোন ও অ্যাপ লক নিরাপদে সেভ করা হয়েছে।", Toast.LENGTH_SHORT).show()
        back()
      },
      modifier = Modifier.fillMaxWidth().height(50.dp).testTag("save_lock_vault_btn"),
      colors = ButtonDefaults.buttonColors(containerColor = CYBER_RED),
    ) {
      Text("সেভ করুন (Save Lock Vault)", fontWeight = FontWeight.Bold)
    }
  }
}

/**
 * Interactive 3x3 touch drag Pattern Lock Pad (dots 1..9) matching Android LockPatternView coordinates.
 */
@Composable
fun PatternLockPad(
  patternDigits: String,
  onPatternChanged: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var padSize by remember { mutableStateOf(IntSize.Zero) }
  var activeDragPos by remember { mutableStateOf<Offset?>(null) }
  var drawingSequence by remember { mutableStateOf(patternDigits) }

  fun dotCenter(index0to8: Int, size: IntSize): Offset {
    val row = index0to8 / 3
    val col = index0to8 % 3
    val side = minOf(size.width, size.height).toFloat()
    val ox = (size.width - side) * 0.5f
    val oy = (size.height - side) * 0.5f
    val cell = side / 3f
    return Offset(ox + cell * (col + 0.5f), oy + cell * (row + 0.5f))
  }

  fun hitTestDot(pos: Offset, size: IntSize): Char? {
    if (size.width <= 0 || size.height <= 0) return null
    val hitRadius = minOf(size.width, size.height) / 7.2f
    for (idx in 0 until 9) {
      val c = dotCenter(idx, size)
      if (hypot(pos.x - c.x, pos.y - c.y) <= hitRadius) {
        return ('1' + idx)
      }
    }
    return null
  }

  fun appendDotWithIntermediate(seq: String, newDot: Char): String {
    if (seq.contains(newDot)) return seq
    if (seq.isEmpty()) return newDot.toString()
    val prevIdx = seq.last() - '1'
    val curIdx = newDot - '1'
    val pr = prevIdx / 3
    val pc = prevIdx % 3
    val cr = curIdx / 3
    val cc = curIdx % 3
    // Automatically include intermediate dot when jumping straight across center (e.g. 1->3 adds 2)
    val mid =
      if ((pr + cr) % 2 == 0 && (pc + cc) % 2 == 0) {
        val mr = (pr + cr) / 2
        val mc = (pc + cc) / 2
        val midChar = ('1' + (mr * 3 + mc))
        if (midChar != newDot && !seq.contains(midChar)) midChar else null
      } else {
        null
      }
    return buildString {
      append(seq)
      if (mid != null) append(mid)
      append(newDot)
    }
  }

  val displayed = if (activeDragPos != null) drawingSequence else patternDigits
  val formattedLabel =
    if (displayed.isEmpty()) "আঙুল টেনে ৩×৩ গ্রিডে প্যাটার্ন আঁকুন"
    else "প্যাটার্ন: ${displayed.toCharArray().joinToString("→")}"

  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = formattedLabel,
      color = if (displayed.length >= 4) CYBER_GREEN else CYBER_AMBER,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
    )

    Box(
      modifier =
        Modifier
          .fillMaxWidth(0.76f)
          .aspectRatio(1f)
          .clip(RoundedCornerShape(18.dp))
          .background(Color(0xFF0A1128))
          .border(1.2.dp, CYBER_CYAN.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
          .onSizeChanged { padSize = it }
          .pointerInput(padSize) {
            detectTapGestures { tapOffset ->
              val d = hitTestDot(tapOffset, padSize)
              if (d != null && !patternDigits.contains(d)) {
                val updated = appendDotWithIntermediate(patternDigits, d).take(9)
                drawingSequence = updated
                onPatternChanged(updated)
              }
            }
          }
          .pointerInput(padSize) {
            detectDragGestures(
              onDragStart = { startOffset ->
                val d = hitTestDot(startOffset, padSize)
                drawingSequence = d?.toString().orEmpty()
                activeDragPos = startOffset
              },
              onDrag = { change, _ ->
                change.consume()
                val pos = change.position
                activeDragPos = pos
                val d = hitTestDot(pos, padSize)
                if (d != null) {
                  drawingSequence = appendDotWithIntermediate(drawingSequence, d).take(9)
                }
              },
              onDragEnd = {
                activeDragPos = null
                if (drawingSequence.isNotEmpty()) {
                  onPatternChanged(drawingSequence)
                }
              },
              onDragCancel = {
                activeDragPos = null
                drawingSequence = patternDigits
              },
            )
          }
    ) {
      Canvas(Modifier.fillMaxSize()) {
        val sz = IntSize(size.width.toInt(), size.height.toInt())
        val seq = displayed
        if (seq.isNotEmpty()) {
          val path = Path()
          seq.forEachIndexed { idx, ch ->
            val pt = dotCenter(ch - '1', sz)
            if (idx == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
          }
          activeDragPos?.let { dragPt ->
            path.lineTo(dragPt.x, dragPt.y)
          }
          drawPath(
            path = path,
            color = CYBER_CYAN,
            style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
          )
        }

        for (i in 0 until 9) {
          val center = dotCenter(i, sz)
          val selected = seq.contains('1' + i)
          drawCircle(
            color = if (selected) CYBER_CYAN.copy(alpha = 0.25f) else Color(0xFF1A2548),
            radius = if (selected) 34f else 24f,
            center = center,
          )
          drawCircle(
            color = if (selected) CYBER_GREEN else Color(0xFF6272A4),
            radius = if (selected) 14f else 10f,
            center = center,
          )
        }
      }
    }

    OutlinedButton(
      onClick = {
        drawingSequence = ""
        onPatternChanged("")
      },
      modifier = Modifier.testTag("clear_pattern_btn"),
    ) {
      Icon(Icons.Filled.Refresh, contentDescription = null, tint = CYBER_AMBER, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(6.dp))
      Text("প্যাটার্ন মুছুন (Clear)", color = CYBER_AMBER, fontSize = 12.sp)
    }
  }
}
