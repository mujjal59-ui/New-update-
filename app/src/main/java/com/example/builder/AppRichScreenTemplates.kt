package com.example.builder

/**
 * Generates genuinely interactive, visually polished Android screens (Compose & XML/Kotlin/Java)
 * tailored to each AppCategory and user prompt description.
 */
object AppRichScreenTemplates {

  fun resolveEffectiveCategory(category: AppCategory, description: String): AppCategory {
    if (category != AppCategory.CUSTOM) return category
    val d = description.lowercase()
    return when {
      d.contains("calc") || d.contains("ক্যালকুলেটর") || d.contains("হিসাব") -> AppCategory.CALCULATOR
      d.contains("game") || d.contains("গেম") || d.contains("tic") || d.contains("puzzle") -> AppCategory.MINI_GAME
      d.contains("ai") || d.contains("assistant") || d.contains("অ্যাসিস্ট্যান্ট") || d.contains("চ্যাট") || d.contains("chat") -> AppCategory.AI_ASSISTANT
      d.contains("note") || d.contains("নোট") || d.contains("diary") -> AppCategory.NOTES
      d.contains("todo") || d.contains("task") || d.contains("টাস্ক") -> AppCategory.PRODUCTIVITY
      d.contains("quiz") || d.contains("কুইজ") || d.contains("study") -> AppCategory.EDUCATION
      else -> AppCategory.CUSTOM
    }
  }

  fun composeMainActivity(
    pkg: String,
    appTitle: String,
    desc: String,
    category: AppCategory,
    theme: AppDesignTheme,
  ): String {
    val effective = resolveEffectiveCategory(category, desc)
    val titleEsc = appTitle.replace("\"", "").replace("$", "")
    val descEsc = desc.take(120).replace("\"", "").replace("$", "")
    val bgHex = "0xFF${theme.surfaceHex.removePrefix("#")}"
    val primaryHex = "0xFF${theme.primaryHex.removePrefix("#")}"
    val accentHex = "0xFF${theme.accentHex.removePrefix("#")}"
    val textHex = "0xFF${theme.textHex.removePrefix("#")}"

    return when (effective) {
      AppCategory.CALCULATOR ->
        """
        package $pkg

        import android.os.Bundle
        import androidx.activity.ComponentActivity
        import androidx.activity.compose.setContent
        import androidx.compose.foundation.background
        import androidx.compose.foundation.clickable
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.Row
        import androidx.compose.foundation.layout.aspectRatio
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.layout.fillMaxWidth
        import androidx.compose.foundation.layout.padding
        import androidx.compose.foundation.shape.CircleShape
        import androidx.compose.foundation.shape.RoundedCornerShape
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Text
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.draw.clip
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.text.font.FontWeight
        import androidx.compose.ui.text.style.TextAlign
        import androidx.compose.ui.text.style.TextOverflow
        import androidx.compose.ui.unit.dp
        import androidx.compose.ui.unit.sp

        class MainActivity : ComponentActivity() {
          override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
              MaterialTheme {
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

                fun onKeyPress(key: String) {
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
                        val flipped = -v
                        val formatted = if (flipped % 1.0 == 0.0) flipped.toLong().toString() else flipped.toString()
                        display = formatted
                        expression = formatted
                      }
                    }
                    "=" -> {
                      val expr = expression.ifBlank { display }
                      val result = evalMath(expr)
                      history = expr + " = " + result
                      display = result
                      expression = if (result == "Error") "" else result
                    }
                    else -> {
                      val opChars = setOf("+", "−", "×", "÷", "%")
                      if (expression.isEmpty() && key in opChars && display != "0" && display != "Error") {
                        expression = display + key
                      } else {
                        expression += key
                      }
                      display = expression
                    }
                  }
                }

                Column(
                  modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0C10))
                    .padding(18.dp),
                  verticalArrangement = Arrangement.Bottom,
                ) {
                  Text(
                    text = "$titleEsc",
                    color = Color($primaryHex),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                  )
                  Text(
                    text = history,
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = display,
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(18.dp))
                      .background(Color(0xFF141822))
                      .padding(horizontal = 18.dp, vertical = 20.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )

                  Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    keypad.forEach { row ->
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                      ) {
                        row.forEach { key ->
                          val isEquals = key == "="
                          val isOp = key in listOf("÷", "×", "−", "+", "%")
                          val isAction = key in listOf("AC", "⌫", "±")
                          val bg = when {
                            isEquals -> Color(0xFFFF9500)
                            isOp -> Color(0xFF262D3D)
                            isAction -> Color(0xFF333C4D)
                            else -> Color(0xFF1B202C)
                          }
                          val fg = when {
                            isEquals -> Color.White
                            isOp -> Color(0xFFFF9F1A)
                            isAction -> Color($primaryHex)
                            else -> Color.White
                          }
                          Box(
                            modifier = Modifier
                              .weight(1f)
                              .aspectRatio(1f)
                              .clip(CircleShape)
                              .background(bg)
                              .clickable { onKeyPress(key) },
                            contentAlignment = Alignment.Center,
                          ) {
                            Text(text = key, color = fg, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          private fun evalMath(raw: String): String {
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
              if (acc % 1.0 == 0.0) acc.toLong().toString() else String.format("%.4f", acc).trimEnd('0').trimEnd('.')
            } catch (_: Exception) {
              "Error"
            }
          }
        }
        """.trimIndent()

      AppCategory.MINI_GAME ->
        """
        package $pkg

        import android.os.Bundle
        import androidx.activity.ComponentActivity
        import androidx.activity.compose.setContent
        import androidx.compose.foundation.background
        import androidx.compose.foundation.border
        import androidx.compose.foundation.clickable
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.Row
        import androidx.compose.foundation.layout.aspectRatio
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.layout.fillMaxWidth
        import androidx.compose.foundation.layout.padding
        import androidx.compose.foundation.shape.RoundedCornerShape
        import androidx.compose.material3.Button
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Text
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
        import androidx.compose.ui.text.font.FontWeight
        import androidx.compose.ui.unit.dp
        import androidx.compose.ui.unit.sp

        class MainActivity : ComponentActivity() {
          override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
              MaterialTheme {
                val board = remember { mutableStateListOf("", "", "", "", "", "", "", "", "") }
                var playerScore by remember { mutableIntStateOf(0) }
                var aiScore by remember { mutableIntStateOf(0) }
                var statusText by remember { mutableStateOf("আপনার চাল (X) — যেকোনো ঘরে ট্যাপ করুন") }
                var gameOver by remember { mutableStateOf(false) }

                fun checkWinner(): String? {
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

                fun resetBoard() {
                  for (i in 0 until 9) board[i] = ""
                  gameOver = false
                  statusText = "আপনার চাল (X) — যেকোনো ঘরে ট্যাপ করুন"
                }

                fun makeMove(idx: Int) {
                  if (gameOver || board[idx].isNotEmpty()) return
                  board[idx] = "X"
                  val w1 = checkWinner()
                  if (w1 == "X") {
                    playerScore++
                    statusText = "অভিনন্দন! আপনি (X) জিতেছেন!"
                    gameOver = true
                    return
                  }
                  val emptyCells = (0 until 9).filter { board[it].isEmpty() }
                  if (emptyCells.isEmpty()) {
                    statusText = "ম্যাচ ড্র হয়েছে!"
                    gameOver = true
                    return
                  }
                  val aiPick = if (4 in emptyCells) 4 else emptyCells.random()
                  board[aiPick] = "O"
                  val w2 = checkWinner()
                  if (w2 == "O") {
                    aiScore++
                    statusText = "AI (O) জিতেছে! আবার চেষ্টা করুন"
                    gameOver = true
                  } else if (board.none { it.isEmpty() }) {
                    statusText = "ম্যাচ ড্র হয়েছে!"
                    gameOver = true
                  }
                }

                Column(
                  modifier = Modifier
                    .fillMaxSize()
                    .background(Color($bgHex))
                    .padding(20.dp),
                  verticalArrangement = Arrangement.spacedBy(16.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Text("$titleEsc", color = Color($primaryHex), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("আপনি (X): " + playerScore, color = Color($primaryHex), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("AI (O): " + aiScore, color = Color($accentHex), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                  }
                  Text(statusText, color = Color($textHex), fontSize = 15.sp, fontWeight = FontWeight.Medium)

                  Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    for (r in 0 until 3) {
                      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        for (c in 0 until 3) {
                          val idx = r * 3 + c
                          val sym = board[idx]
                          Box(
                            modifier = Modifier
                              .weight(1f)
                              .aspectRatio(1f)
                              .clip(RoundedCornerShape(16.dp))
                              .background(Color(0xFF16213E))
                              .border(1.5.dp, Color($primaryHex).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                              .clickable { makeMove(idx) },
                            contentAlignment = Alignment.Center,
                          ) {
                            Text(
                              text = sym,
                              color = if (sym == "X") Color($primaryHex) else Color($accentHex),
                              fontSize = 36.sp,
                              fontWeight = FontWeight.ExtraBold,
                            )
                          }
                        }
                      }
                    }
                  }

                  Button(onClick = { resetBoard() }, modifier = Modifier.fillMaxWidth()) {
                    Text("নতুন রাউন্ড শুরু করুন")
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

      AppCategory.AI_ASSISTANT, AppCategory.CHATTING, AppCategory.VOICE_ASSISTANT ->
        """
        package $pkg

        import android.os.Bundle
        import androidx.activity.ComponentActivity
        import androidx.activity.compose.setContent
        import androidx.compose.foundation.background
        import androidx.compose.foundation.clickable
        import androidx.compose.foundation.horizontalScroll
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.Row
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.layout.fillMaxWidth
        import androidx.compose.foundation.layout.padding
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.foundation.lazy.items
        import androidx.compose.foundation.rememberScrollState
        import androidx.compose.foundation.shape.RoundedCornerShape
        import androidx.compose.material3.Button
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.OutlinedTextField
        import androidx.compose.material3.Text
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableStateListOf
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.draw.clip
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.text.font.FontWeight
        import androidx.compose.ui.unit.dp
        import androidx.compose.ui.unit.sp
        import java.text.SimpleDateFormat
        import java.util.Date
        import java.util.Locale

        class MainActivity : ComponentActivity() {
          override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
              MaterialTheme {
                var input by remember { mutableStateOf("") }
                val chatMessages = remember {
                  mutableStateListOf(Pair(false, "হ্যালো! আমি $titleEsc — আপনার স্মার্ট অ্যাসিস্ট্যান্ট। যেকোনো প্রশ্ন বা হিসাব জিজ্ঞেস করুন!"))
                }
                val chips = listOf("সময় কত?", "২৫*৪ হিসাব করো", "কবিতা শোনাও", "কাজের আইডিয়া")

                fun sendPrompt(rawText: String) {
                  val q = rawText.trim()
                  if (q.isEmpty()) return
                  chatMessages.add(Pair(true, q))
                  chatMessages.add(Pair(false, buildSmartReply(q)))
                }

                Column(
                  modifier = Modifier
                    .fillMaxSize()
                    .background(Color($bgHex))
                    .padding(16.dp),
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Text("$titleEsc", color = Color($primaryHex), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                  Text("অনলাইন • স্মার্ট এআই চ্যাট", color = Color(0xFF10B981), fontSize = 12.sp)

                  Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    chips.forEach { chip ->
                      Box(
                        modifier = Modifier
                          .clip(RoundedCornerShape(20.dp))
                          .background(Color(0xFF1E293B))
                          .clickable { sendPrompt(chip) }
                          .padding(horizontal = 12.dp, vertical = 7.dp),
                      ) {
                        Text(chip, color = Color($primaryHex), fontSize = 12.sp)
                      }
                    }
                  }

                  LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    items(chatMessages) { (isUser, msg) ->
                      Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
                      ) {
                        Text(
                          text = msg,
                          color = if (isUser) Color.Black else Color($textHex),
                          fontSize = 14.sp,
                          modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isUser) Color($primaryHex) else Color(0xFF1E293B))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                      }
                    }
                  }

                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                      value = input,
                      onValueChange = { input = it },
                      modifier = Modifier.weight(1f),
                      label = { Text("প্রশ্ন লিখুন...") },
                      singleLine = true,
                    )
                    Button(onClick = {
                      val t = input
                      input = ""
                      sendPrompt(t)
                    }) {
                      Text("পাঠান")
                    }
                  }
                }
              }
            }
          }

          private fun buildSmartReply(q: String): String {
            val l = q.lowercase()
            val timeStr = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date())
            val mathMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(\\d+(?:\\.\\d+)?)").find(q)
            if (mathMatch != null) {
              val a = mathMatch.groupValues[1].toDoubleOrNull() ?: 0.0
              val b = mathMatch.groupValues[3].toDoubleOrNull() ?: 0.0
              val res = when (mathMatch.groupValues[2]) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> if (b != 0.0) a / b else 0.0
                else -> 0.0
              }
              return "উত্তর: " + if (res % 1.0 == 0.0) res.toLong().toString() else res.toString()
            }
            return when {
              l.contains("সময়") || l.contains("time") -> "এখন সময় হলো " + timeStr + "।"
              l.contains("কবিতা") -> "মেঘলা আকাশে স্বপ্নের ভেলা, কাজ করে যাও সারাবেলা।"
              l.contains("আইডিয়া") -> "আপনি দৈনিক টাস্ক ট্র্যাকার বা নোটস প্রজেক্ট নিয়ে কাজ শুরু করতে পারেন!"
              else -> "আপনার বার্তা পেয়েছি: \"" + q + "\"। আমি সাহায্য করতে সর্বদা প্রস্তুত!"
            }
          }
        }
        """.trimIndent()

      else ->
        """
        package $pkg

        import android.os.Bundle
        import androidx.activity.ComponentActivity
        import androidx.activity.compose.setContent
        import androidx.compose.foundation.background
        import androidx.compose.foundation.border
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.Row
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.layout.fillMaxWidth
        import androidx.compose.foundation.layout.padding
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.foundation.lazy.itemsIndexed
        import androidx.compose.foundation.shape.RoundedCornerShape
        import androidx.compose.material3.Button
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.OutlinedTextField
        import androidx.compose.material3.Text
        import androidx.compose.material3.TextButton
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableStateListOf
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.draw.clip
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.text.font.FontWeight
        import androidx.compose.ui.unit.dp
        import androidx.compose.ui.unit.sp

        class MainActivity : ComponentActivity() {
          override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
              MaterialTheme {
                var titleInput by remember { mutableStateOf("") }
                var bodyInput by remember { mutableStateOf("") }
                val items = remember { mutableStateListOf<Pair<String, String>>() }

                Column(
                  modifier = Modifier
                    .fillMaxSize()
                    .background(Color($bgHex))
                    .padding(18.dp),
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Text("$titleEsc", color = Color($primaryHex), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                  Text("$descEsc", color = Color($textHex), fontSize = 13.sp)
                  OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("শিরোনাম / আইটেম") },
                    singleLine = true,
                  )
                  OutlinedTextField(
                    value = bodyInput,
                    onValueChange = { bodyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${effective.titleBn} বিবরণ") },
                  )
                  Button(
                    onClick = {
                      val t = titleInput.trim().ifEmpty { effective.titleEn }
                      val b = bodyInput.trim()
                      if (b.isNotEmpty() || titleInput.isNotBlank()) {
                        items.add(0, Pair(t, b.ifEmpty { t }))
                        titleInput = ""
                        bodyInput = ""
                      }
                    },
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Text("সংরক্ষণ ও যোগ করুন")
                  }
                  LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(items) { idx, entry ->
                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(12.dp))
                          .background(Color(0xFF141B2D))
                          .border(1.dp, Color($primaryHex).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                          .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          Text(entry.first, color = Color($primaryHex), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                          Text(entry.second, color = Color($textHex), fontSize = 13.sp)
                        }
                        TextButton(onClick = { items.removeAt(idx) }) {
                          Text("মুছুন", color = Color($accentHex))
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        """.trimIndent()
    }
  }
}
