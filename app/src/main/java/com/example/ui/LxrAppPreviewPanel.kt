package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.builder.AppCategory
import com.example.builder.AppDesignTheme
import com.example.builder.AppRichScreenTemplates
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LxrLiveAppPreviewCard(
  appName: String,
  description: String,
  category: AppCategory,
  designTheme: AppDesignTheme,
  isBuilding: Boolean,
  stepLabel: String,
) {
  val effective = AppRichScreenTemplates.resolveEffectiveCategory(category, description)
  val primaryColor = Color(android.graphics.Color.parseColor(designTheme.primaryHex))
  val accentColor = Color(android.graphics.Color.parseColor(designTheme.accentHex))
  val bgColor = Color(android.graphics.Color.parseColor(designTheme.surfaceHex))
  val textColor = Color(android.graphics.Color.parseColor(designTheme.textHex))

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .testTag("builder_live_preview_card")
        .clip(RoundedCornerShape(18.dp))
        .background(Color(0xFF090F22))
        .border(1.4.dp, CYBER_CYAN.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = CYBER_CYAN, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(
        text = "📱 লাইভ অ্যাপ প্রিভিউ ও ইন্টারেক্টিভ ডেমো",
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 13.5.sp,
        modifier = Modifier.weight(1f),
      )
      Row(
        modifier =
          Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isBuilding) CYBER_AMBER.copy(alpha = 0.2f) else CYBER_GREEN.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          Icons.Filled.PlayArrow,
          contentDescription = null,
          tint = if (isBuilding) CYBER_AMBER else CYBER_GREEN,
          modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
          text = if (isBuilding) "Live Build" else "Interactive",
          color = if (isBuilding) CYBER_AMBER else CYBER_GREEN,
          fontSize = 10.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    if (stepLabel.isNotBlank()) {
      Text(
        text = stepLabel,
        color = Color(0xFF93C5FD),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
      )
    }

    // Smartphone Bezel Frame
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(24.dp))
          .background(Color(0xFF04060C))
          .border(2.dp, Color(0xFF334155), RoundedCornerShape(24.dp))
          .padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Simulated phone top speaker notch + status bar
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("LXR OS", color = Color(0xFF64748B), fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        Box(
          modifier =
            Modifier
              .width(52.dp)
              .height(10.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(Color(0xFF1E293B))
        )
        Text(effective.titleEn.take(14), color = primaryColor, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
      }

      // Interactive App Viewport
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (effective == AppCategory.CALCULATOR) Color(0xFF0A0C10) else bgColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        when (effective) {
          AppCategory.CALCULATOR -> CalculatorPreviewUi(appName = appName, primaryColor = primaryColor)
          AppCategory.MINI_GAME -> MiniGamePreviewUi(appName = appName, primaryColor = primaryColor, accentColor = accentColor, textColor = textColor)
          AppCategory.AI_ASSISTANT, AppCategory.CHATTING, AppCategory.VOICE_ASSISTANT ->
            AiChatPreviewUi(appName = appName, primaryColor = primaryColor, textColor = textColor)
          else -> GenericInteractivePreviewUi(appName = appName, category = effective, primaryColor = primaryColor, accentColor = accentColor, textColor = textColor)
        }
      }
    }
  }
}

@Composable
private fun CalculatorPreviewUi(appName: String, primaryColor: Color) {
  var expression by remember { mutableStateOf("") }
  var display by remember { mutableStateOf("0") }
  var history by remember { mutableStateOf("প্রস্তুত") }

  val keypad = listOf(
    listOf("AC", "⌫", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("±", "0", ".", "="),
  )

  fun press(key: String) {
    when (key) {
      "AC" -> {
        expression = ""
        display = "0"
      }
      "⌫" -> {
        if (expression.isNotEmpty()) {
          expression = expression.dropLast(1)
          display = expression.ifEmpty { "0" }
        }
      }
      "±" -> {
        val v = display.toDoubleOrNull()
        if (v != null && v != 0.0) {
          val f = -v
          val fmt = if (f % 1.0 == 0.0) f.toLong().toString() else f.toString()
          expression = fmt
          display = fmt
        }
      }
      "=" -> {
        val src = expression.ifBlank { display }
        val res = evalPreviewMath(src)
        history = "$src = $res"
        display = res
        expression = if (res == "Error") "" else res
      }
      else -> {
        val ops = setOf("+", "−", "×", "÷", "%")
        if (expression.isEmpty() && key in ops && display != "0" && display != "Error") {
          expression = display + key
        } else {
          expression += key
        }
        display = expression
      }
    }
  }

  Text(
    text = appName.ifBlank { "Amar Calculator" },
    color = primaryColor,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold,
  )
  Text(
    text = history,
    color = Color(0xFF94A3B8),
    fontSize = 11.sp,
    textAlign = TextAlign.End,
    modifier = Modifier.fillMaxWidth(),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
  Text(
    text = display,
    color = Color.White,
    fontSize = 30.sp,
    fontWeight = FontWeight.Light,
    textAlign = TextAlign.End,
    modifier =
      Modifier
        .fillMaxWidth()
        .testTag("preview_calc_display")
        .clip(RoundedCornerShape(14.dp))
        .background(Color(0xFF141822))
        .padding(horizontal = 14.dp, vertical = 12.dp),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )

  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    keypad.forEach { row ->
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { key ->
          val isEq = key == "="
          val isOp = key in listOf("÷", "×", "−", "+", "%")
          val isAct = key in listOf("AC", "⌫", "±")
          val bg = when {
            isEq -> Color(0xFFFF9500)
            isOp -> Color(0xFF262D3D)
            isAct -> Color(0xFF333C4D)
            else -> Color(0xFF1B202C)
          }
          val fg = when {
            isEq -> Color.White
            isOp -> Color(0xFFFF9F1A)
            isAct -> primaryColor
            else -> Color.White
          }
          Box(
            modifier =
              Modifier
                .weight(1f)
                .aspectRatio(1.15f)
                .testTag("preview_calc_key_$key")
                .clip(CircleShape)
                .background(bg)
                .clickable { press(key) },
            contentAlignment = Alignment.Center,
          ) {
            Text(text = key, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}

private fun evalPreviewMath(raw: String): String {
  return try {
    val clean = raw.replace("×", "*").replace("÷", "/").replace("−", "-")
    val tokens = Regex("(-?\\d+(?:\\.\\d+)?|[+\\-*/%])").findAll(clean).map { it.value }.toList()
    if (tokens.isEmpty()) return "0"
    var acc = tokens[0].toDouble()
    var i = 1
    while (i + 1 < tokens.size) {
      val op = tokens[i]
      val rhs = tokens[i + 1].toDouble()
      acc = when (op) {
        "+" -> acc + rhs
        "-" -> acc - rhs
        "*" -> acc * rhs
        "/" -> if (rhs == 0.0) return "Error" else acc / rhs
        "%" -> acc % rhs
        else -> acc
      }
      i += 2
    }
    if (acc % 1.0 == 0.0) acc.toLong().toString()
    else String.format(Locale.US, "%.4f", acc).trimEnd('0').trimEnd('.')
  } catch (_: Exception) {
    "Error"
  }
}

@Composable
private fun MiniGamePreviewUi(
  appName: String,
  primaryColor: Color,
  accentColor: Color,
  textColor: Color,
) {
  val board = remember { mutableStateListOf("", "", "", "", "", "", "", "", "") }
  var playerScore by remember { mutableIntStateOf(0) }
  var aiScore by remember { mutableIntStateOf(0) }
  var status by remember { mutableStateOf("আপনার চাল (X) — ঘরে ট্যাপ করুন") }
  var over by remember { mutableStateOf(false) }

  fun winner(): String? {
    val lines = listOf(
      listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
      listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
      listOf(0, 4, 8), listOf(2, 4, 6),
    )
    for (l in lines) {
      val a = board[l[0]]
      if (a.isNotEmpty() && a == board[l[1]] && a == board[l[2]]) return a
    }
    return null
  }

  fun play(idx: Int) {
    if (over || board[idx].isNotEmpty()) return
    board[idx] = "X"
    if (winner() == "X") {
      playerScore++
      over = true
      status = "অভিনন্দন! আপনি (X) জিতেছেন!"
      return
    }
    val empty = (0 until 9).filter { board[it].isEmpty() }
    if (empty.isEmpty()) {
      over = true
      status = "ম্যাচ ড্র হয়েছে!"
      return
    }
    val pick = if (4 in empty) 4 else empty.random()
    board[pick] = "O"
    if (winner() == "O") {
      aiScore++
      over = true
      status = "AI (O) জিতেছে! আবার খেলুন"
    } else if (board.none { it.isEmpty() }) {
      over = true
      status = "ম্যাচ ড্র হয়েছে!"
    }
  }

  Text(appName.ifBlank { "LXR Tic-Tac-Toe" }, color = primaryColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
    Text("আপনি (X): $playerScore", color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Text("AI (O): $aiScore", color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
  }
  Text(status, color = textColor, fontSize = 11.5.sp)

  Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
    for (r in 0 until 3) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for (c in 0 until 3) {
          val idx = r * 3 + c
          val sym = board[idx]
          Box(
            modifier =
              Modifier
                .weight(1f)
                .height(54.dp)
                .testTag("preview_game_cell_$idx")
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF16213E))
                .border(1.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable { play(idx) },
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = sym,
              color = if (sym == "X") primaryColor else accentColor,
              fontSize = 22.sp,
              fontWeight = FontWeight.ExtraBold,
            )
          }
        }
      }
    }
  }

  Button(
    onClick = {
      for (i in 0 until 9) board[i] = ""
      over = false
      status = "আপনার চাল (X) — ঘরে ট্যাপ করুন"
    },
    modifier = Modifier.fillMaxWidth().testTag("preview_game_reset"),
    colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = Color.Black),
  ) {
    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
    Spacer(Modifier.width(5.dp))
    Text("নতুন গেম", fontSize = 12.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun AiChatPreviewUi(appName: String, primaryColor: Color, textColor: Color) {
  var input by remember { mutableStateOf("") }
  val messages = remember {
    mutableStateListOf(
      false to "হ্যালো! আমি ${appName.ifBlank { "LXR AI Assistant" }}। প্রশ্ন বা অংক লিখুন!"
    )
  }
  val chips = listOf("সময় কত?", "২৫*৪ হিসাব", "কবিতা", "আইডিয়া")

  fun send(q: String) {
    val clean = q.trim()
    if (clean.isEmpty()) return
    messages.add(true to clean)
    val l = clean.lowercase()
    val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    val reply = when {
      l.contains("*") || l.contains("+") || l.contains("-") || l.contains("/") -> {
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)").find(clean)
        if (m != null) "উত্তর: ${evalPreviewMath(m.value)}" else "হিসাব করতে সংখ্যা লিখুন যেমন 25*4"
      }
      l.contains("সময়") -> "এখন সময় $timeStr।"
      l.contains("কবিতা") -> "মেঘলা আকাশে স্বপ্নের ভেলা, এগিয়ে চলো সারাবেলা।"
      else -> "আপনার কথা \"$clean\" বুঝেছি — আমি সর্বদা সাহায্য করতে প্রস্তুত!"
    }
    messages.add(false to reply)
  }

  Text(appName.ifBlank { "LXR AI Assistant" }, color = primaryColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
  Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    chips.forEach { chip ->
      Box(
        modifier =
          Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E293B))
            .clickable { send(chip) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
      ) {
        Text(chip, color = primaryColor, fontSize = 11.sp)
      }
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
    messages.takeLast(4).forEach { (isUser, msg) ->
      Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
          text = msg,
          color = if (isUser) Color.Black else textColor,
          fontSize = 11.5.sp,
          modifier =
            Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(if (isUser) primaryColor else Color(0xFF1E293B))
              .padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
      value = input,
      onValueChange = { input = it },
      modifier = Modifier.weight(1f),
      label = { Text("প্রশ্ন লিখুন...", fontSize = 11.sp) },
      singleLine = true,
      colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, focusedBorderColor = primaryColor, unfocusedBorderColor = Color(0xFF475569)),
    )
    Button(
      onClick = {
        val t = input
        input = ""
        send(t)
      },
      colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = Color.Black),
    ) {
      Text("পাঠান", fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun GenericInteractivePreviewUi(
  appName: String,
  category: AppCategory,
  primaryColor: Color,
  accentColor: Color,
  textColor: Color,
) {
  var title by remember { mutableStateOf("") }
  var body by remember { mutableStateOf("") }
  val entries = remember { mutableStateListOf<Pair<String, String>>() }

  Text(appName.ifBlank { category.titleEn }, color = primaryColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
  OutlinedTextField(
    value = title,
    onValueChange = { title = it },
    modifier = Modifier.fillMaxWidth(),
    label = { Text("শিরোনাম", fontSize = 11.sp) },
    singleLine = true,
    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, focusedBorderColor = primaryColor, unfocusedBorderColor = Color(0xFF475569)),
  )
  OutlinedTextField(
    value = body,
    onValueChange = { body = it },
    modifier = Modifier.fillMaxWidth(),
    label = { Text("${category.titleBn} লিখুন...", fontSize = 11.sp) },
    singleLine = true,
    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, focusedBorderColor = primaryColor, unfocusedBorderColor = Color(0xFF475569)),
  )
  Button(
    onClick = {
      val b = body.trim()
      val t = title.trim().ifEmpty { category.titleEn }
      if (b.isNotEmpty() || title.isNotBlank()) {
        entries.add(0, t to b.ifEmpty { t })
        title = ""
        body = ""
      }
    },
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = Color.Black),
  ) {
    Text("যোগ করুন", fontSize = 12.sp, fontWeight = FontWeight.Bold)
  }
  entries.take(3).forEachIndexed { idx, (t, b) ->
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(Color(0xFF141B2D))
          .padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(t, color = primaryColor, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        Text(b, color = textColor, fontSize = 11.sp)
      }
      TextButton(onClick = { entries.removeAt(idx) }) {
        Text("মুছুন", color = accentColor, fontSize = 11.sp)
      }
    }
  }
}
