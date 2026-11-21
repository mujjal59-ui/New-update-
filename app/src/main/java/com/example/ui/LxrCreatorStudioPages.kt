package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.service.LxrDownloadStorage
import com.example.service.LxrProviderRegistry
import com.example.service.LxrResearchEngine
import com.example.service.LxrStorageCategory
import com.example.service.LxrStudioHandoff
import com.example.service.YoutubeSeoPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rounded octagonal / chamfered hexagonal futuristic card shape matching the LXR--AI dock cards.
 */
class ChamferedFuturisticShape(
  private val chamferDp: Dp = 12.dp,
  private val cornerRadiusDp: Dp = 4.dp,
) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline {
    val w = size.width
    val h = size.height
    val c = with(density) { chamferDp.toPx() }.coerceAtMost(minOf(w, h) * 0.28f)
    val r = with(density) { cornerRadiusDp.toPx() }.coerceAtMost(c * 0.45f)
    val path = buildChamferedPath(w, h, c, r)
    return Outline.Generic(path)
  }
}

private fun buildChamferedPath(w: Float, h: Float, c: Float, r: Float): Path {
  val path = Path()
  // Top edge -> Top-right chamfer -> Right edge -> Bottom-right chamfer -> Bottom edge -> ...
  val diag = r * 0.7071f
  path.moveTo(c + r, 0f)
  path.lineTo(w - c - r, 0f)
  path.quadraticTo(w - c, 0f, w - c + diag, diag)
  path.lineTo(w - diag, c - diag)
  path.quadraticTo(w, c, w, c + r)

  path.lineTo(w, h - c - r)
  path.quadraticTo(w, h - c, w - diag, h - c + diag)
  path.lineTo(w - c + diag, h - diag)
  path.quadraticTo(w - c, h, w - c - r, h)

  path.lineTo(c + r, h)
  path.quadraticTo(c, h, c - diag, h - diag)
  path.lineTo(diag, h - c + diag)
  path.quadraticTo(0f, h - c, 0f, h - c - r)

  path.lineTo(0f, c + r)
  path.quadraticTo(0f, c, diag, c - diag)
  path.lineTo(c - diag, diag)
  path.quadraticTo(c, 0f, c + r, 0f)
  path.close()
  return path
}

@Composable
fun LxrThreeSectionRow(
  cardHeight: Dp,
  isCompactWidth: Boolean,
  onAppBuilder: () -> Unit,
  onVideoCreator: () -> Unit,
  onSeoStudio: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val appBuilderLabel = stringResource(R.string.section_btn_app_builder)
  val videoCreatorLabel = stringResource(R.string.section_btn_video_creator)
  val seoStudioLabel = stringResource(R.string.section_btn_youtube_seo)
  val seoStudioCd = stringResource(R.string.section_btn_youtube_seo_cd)

  val singleLineFont = if (isCompactWidth) 11.5.sp else 13.sp
  val twoLineFont = if (isCompactWidth) 10.5.sp else 12.sp
  val iconBoxSize = if (cardHeight < 74.dp) 26.dp else 30.dp

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 1. অ্যাপস তৈরি — Purple Glow
      FuturisticNeonSectionCard(
        label = appBuilderLabel,
        accessibilityLabel = appBuilderLabel,
        testTagName = "section_btn_app_builder",
        cardHeight = cardHeight,
        fontSize = singleLineFont,
        lineHeight = 15.sp,
        borderColors = listOf(Color(0xFFC084FC), Color(0xFF8B5CF6), Color(0xFF7E22CE)),
        fillColors = listOf(Color(0xED3B0764), Color(0xEE1A0836), Color(0xF60A0518)),
        glowColors = listOf(Color(0xFFA855F7), Color(0xFF7E22CE)),
        onClick = onAppBuilder,
        modifier = Modifier.weight(1f),
      ) {
        PremiumAppGridIcon(modifier = Modifier.size(iconBoxSize))
      }

      // 2. ভিডিও তৈরি — Cyan Glow
      FuturisticNeonSectionCard(
        label = videoCreatorLabel,
        accessibilityLabel = videoCreatorLabel,
        testTagName = "section_btn_video_creator",
        cardHeight = cardHeight,
        fontSize = singleLineFont,
        lineHeight = 15.sp,
        borderColors = listOf(Color(0xFF38BDF8), Color(0xFF00E5FF), Color(0xFF0284C7)),
        fillColors = listOf(Color(0xED083344), Color(0xEE081E38), Color(0xF6040C1B)),
        glowColors = listOf(Color(0xFF00E5FF), Color(0xFF0EA5E9)),
        onClick = onVideoCreator,
        modifier = Modifier.weight(1f),
      ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(iconBoxSize)) {
          Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconBoxSize),
          )
        }
      }

      // 3. YouTube ভিডিও SEO — Purple + Magenta Glow (v2.22.0 exact)
      FuturisticNeonSectionCard(
        label = seoStudioLabel,
        accessibilityLabel = seoStudioCd,
        testTagName = "section_btn_seo_studio",
        cardHeight = cardHeight,
        fontSize = twoLineFont,
        lineHeight = if (isCompactWidth) 12.5.sp else 14.sp,
        borderColors = listOf(Color(0xFFA855F7), Color(0xFFFF179E), Color(0xFFD946EF)),
        fillColors = listOf(Color(0xED4A044E), Color(0xEE220736), Color(0xF60E041A)),
        glowColors = listOf(Color(0xFFFF179E), Color(0xFF9C27B0)),
        onClick = onSeoStudio,
        modifier = Modifier.weight(1f),
      ) {
        PremiumYouTubeBadgeIcon(modifier = Modifier.size(width = iconBoxSize + 6.dp, height = iconBoxSize - 2.dp))
      }
    }

    Spacer(Modifier.height(7.dp))
    HomeBottomNeonDivider()
  }
}

@Composable
private fun FuturisticNeonSectionCard(
  label: String,
  accessibilityLabel: String,
  testTagName: String,
  cardHeight: Dp,
  fontSize: TextUnit,
  lineHeight: TextUnit,
  borderColors: List<Color>,
  fillColors: List<Color>,
  glowColors: List<Color>,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  iconContent: @Composable () -> Unit,
) {
  val cardShape = remember { ChamferedFuturisticShape(chamferDp = 12.dp, cornerRadiusDp = 3.5.dp) }
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  var isHovered by remember { mutableStateOf(false) }

  val scale by animateFloatAsState(
    targetValue =
      when {
        isPressed -> 0.94f
        isHovered -> 1.02f
        else -> 1f
      },
    animationSpec =
      spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
      ),
    label = "section_card_scale",
  )

  val auraInf = rememberInfiniteTransition(label = "section_neon_pulse")
  val breathingPulse by
    auraInf.animateFloat(
      initialValue = 0.85f,
      targetValue = 1.20f,
      animationSpec = infiniteRepeatable(tween(2100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "section_breathing",
    )

  val glowIntensity by animateFloatAsState(
    targetValue =
      when {
        isPressed -> 1.40f
        isHovered -> 1.24f
        else -> breathingPulse
      },
    label = "section_card_glow",
  )

  Box(
    modifier =
      modifier
        .height(cardHeight)
        .heightIn(min = 60.dp)
        .testTag(testTagName)
        .semantics { contentDescription = accessibilityLabel }
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              when (event.type) {
                PointerEventType.Enter -> isHovered = true
                PointerEventType.Exit -> isHovered = false
              }
            }
          }
        }
        .drawBehind {
          val w = size.width
          val h = size.height
          val c = 12.dp.toPx().coerceAtMost(minOf(w, h) * 0.28f)
          val r = 3.5.dp.toPx().coerceAtMost(c * 0.45f)
          val path = buildChamferedPath(w, h, c, r)

          // Multi-layer outer neon aura
          val primaryGlow = glowColors.first()
          val secondaryGlow = glowColors.last()
          drawPath(
            path = path,
            brush = Brush.linearGradient(listOf(primaryGlow.copy(alpha = (0.16f * glowIntensity).coerceAtMost(0.42f)), secondaryGlow.copy(alpha = (0.16f * glowIntensity).coerceAtMost(0.42f)))),
            style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
          )
          drawPath(
            path = path,
            brush = Brush.linearGradient(listOf(primaryGlow.copy(alpha = (0.28f * glowIntensity).coerceAtMost(0.60f)), secondaryGlow.copy(alpha = (0.28f * glowIntensity).coerceAtMost(0.60f)))),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
          )

          // Dark jewel radial interior fill
          drawPath(
            path = path,
            brush =
              Brush.radialGradient(
                colors = fillColors,
                center = Offset(w * 0.5f, h * 0.42f),
                radius = maxOf(w, h) * 0.85f,
              ),
          )

          // Subtle top inner sheen
          drawPath(
            path = path,
            brush =
              Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                startY = 0f,
                endY = h * 0.45f,
              ),
          )

          // Crisp primary neon border
          drawPath(
            path = path,
            brush = Brush.linearGradient(borderColors),
            style = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
          )
        }
        .clip(cardShape)
        .clickable(
          interactionSource = interactionSource,
          indication = LocalIndication.current,
          role = Role.Button,
          onClick = onClick,
        )
        .padding(horizontal = 6.dp, vertical = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      iconContent()
      Spacer(Modifier.height(5.dp))
      Text(
        text = label,
        color = Color.White,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        softWrap = true,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun PremiumAppGridIcon(modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val s = minOf(size.width, size.height)
    val pad = s * 0.10f
    val gap = s * 0.14f
    val tile = (s - pad * 2f - gap) / 2f
    val strokeW = (s * 0.085f).coerceAtLeast(1.8.dp.toPx())
    val rad = CornerRadius(tile * 0.28f, tile * 0.28f)
    val startX = (size.width - s) / 2f + pad
    val startY = (size.height - s) / 2f + pad

    for (row in 0..1) {
      for (col in 0..1) {
        val left = startX + col * (tile + gap)
        val top = startY + row * (tile + gap)
        drawRoundRect(
          color = Color(0x66C084FC),
          topLeft = Offset(left, top),
          size = Size(tile, tile),
          cornerRadius = rad,
          style = Stroke(width = strokeW * 1.9f),
        )
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(left, top),
          size = Size(tile, tile),
          cornerRadius = rad,
          style = Stroke(width = strokeW),
        )
      }
    }
  }
}

@Composable
private fun PremiumYouTubeBadgeIcon(modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val corner = CornerRadius(h * 0.30f, h * 0.30f)

    // Soft magenta/red outer glow
    drawRoundRect(
      color = Color(0x55FF1744),
      topLeft = Offset(0f, 0f),
      size = Size(w, h),
      cornerRadius = corner,
    )
    drawRoundRect(
      brush = Brush.verticalGradient(listOf(Color(0xFFFF1744), Color(0xFFD50000))),
      topLeft = Offset(w * 0.05f, h * 0.06f),
      size = Size(w * 0.90f, h * 0.88f),
      cornerRadius = corner,
    )

    // Centered crisp white play triangle
    val cx = w * 0.52f
    val cy = h * 0.50f
    val triR = minOf(w, h) * 0.25f
    val tri =
      Path().apply {
        moveTo(cx - triR * 0.72f, cy - triR * 0.90f)
        lineTo(cx + triR * 0.95f, cy)
        lineTo(cx - triR * 0.72f, cy + triR * 0.90f)
        close()
      }
    drawPath(tri, color = Color.White)
  }
}

@Composable
fun HomeBottomNeonDivider(modifier: Modifier = Modifier) {
  Canvas(
    modifier =
      modifier
        .fillMaxWidth()
        .height(12.dp)
        .padding(horizontal = 8.dp)
        .testTag("home_section_neon_divider")
  ) {
    val cy = size.height / 2f
    val cx = size.width / 2f

    // Soft cyan ambient glow line
    drawLine(
      brush =
        Brush.horizontalGradient(
          listOf(
            Color.Transparent,
            Color(0x550091EA),
            Color(0x8800E5FF),
            Color(0x550091EA),
            Color.Transparent,
          )
        ),
      start = Offset(0f, cy),
      end = Offset(size.width, cy),
      strokeWidth = 5.dp.toPx(),
      cap = StrokeCap.Round,
    )

    // Crisp inner cyan line
    drawLine(
      brush =
        Brush.horizontalGradient(
          listOf(
            Color.Transparent,
            Color(0xFF0091EA),
            Color(0xFF00E5FF),
            Color(0xFF0091EA),
            Color.Transparent,
          )
        ),
      start = Offset(0f, cy),
      end = Offset(size.width, cy),
      strokeWidth = 1.6.dp.toPx(),
      cap = StrokeCap.Round,
    )

    // Center glowing diamond star
    val dW = 8.dp.toPx()
    val dH = 5.2.dp.toPx()
    val diamond =
      Path().apply {
        moveTo(cx, cy - dH)
        lineTo(cx + dW, cy)
        lineTo(cx, cy + dH)
        lineTo(cx - dW, cy)
        close()
      }
    drawPath(diamond, color = Color(0x9900E5FF))
    val core =
      Path().apply {
        moveTo(cx, cy - dH * 0.65f)
        lineTo(cx + dW * 0.65f, cy)
        lineTo(cx, cy + dH * 0.65f)
        lineTo(cx - dW * 0.65f, cy)
        close()
      }
    drawPath(core, color = Color(0xFFE0FFFF))
  }
}

@Composable
fun AppBuilderScreen(back: () -> Unit, onNavigate: (String) -> Unit) {
  LxrAppBuilderWorkspace(back = back, onNavigate = onNavigate)
}

@Composable
fun VideoCreatorScreen(back: () -> Unit, onNavigate: (String) -> Unit) {
  VideoCreatorStudioScreen(back = back, onNavigate = onNavigate)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeoStudioScreen(back: () -> Unit, onNavigate: (String) -> Unit) {
  BackHandler(onBack = back)
  val ctx = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()

  val title = stringResource(R.string.seo_studio_screen_title)
  val subtitle = stringResource(R.string.seo_studio_screen_subtitle)
  val statusNote = stringResource(R.string.seo_studio_status_note)
  val badgeLabel = stringResource(R.string.studio_ui_only_badge)
  val backHomeLabel = stringResource(R.string.back_to_home_label)

  val accentPrimary = Color(0xFFFF179E)
  val accentSecondary = CYBER_PURPLE

  var topic by remember { mutableStateOf("") }
  var audienceGoal by remember { mutableStateOf("সাধারণ দর্শক ও সার্চ ট্রাফিক") }
  var languageLabel by remember { mutableStateOf("বাংলা ও ইংরেজি (Bilingual)") }
  var loading by remember { mutableStateOf(false) }
  var seoResult by remember { mutableStateOf<YoutubeSeoPackage?>(null) }
  var exportMessage by remember { mutableStateOf("") }
  val handoff by LxrStudioHandoff.latestVideoHandoff.collectAsState()
  val providers = remember { LxrProviderRegistry.inspectProviders(ctx) }

  fun copyBlock(label: String, content: String) {
    clipboard.setText(AnnotatedString(content))
    Toast.makeText(ctx, "$label কপি হয়েছে ✓", Toast.LENGTH_SHORT).show()
  }

  fun runSeoGeneration() {
    val cleanTopic = topic.trim().ifBlank { "AI Bangla Tutorial" }
    if (topic.isBlank()) topic = cleanTopic
    loading = true
    exportMessage = ""
    scope.launch {
      val result =
        withContext(Dispatchers.IO) {
          LxrResearchEngine.generateYoutubeSeoPackage(
            context = ctx,
            topic = cleanTopic,
            languageLabel = languageLabel,
            audienceGoal = audienceGoal.ifBlank { "সাধারণ দর্শক" },
          )
        }
      seoResult = result
      loading = false
      exportMessage = "তৈরি সম্পন্ন ✓ (${result.providerLabel} • ${result.checkedAtDhaka})"
    }
  }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .testTag("seo_studio_screen")
        .background(FULL_BG_GRADIENT)
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(
      modifier =
        Modifier
          .weight(1f)
          .widthIn(max = 600.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SubPageTopBar(
        title = title,
        icon = Icons.Filled.PlayArrow,
        iconColors = listOf(accentPrimary, accentSecondary),
        onBack = back,
        onNavigate = onNavigate,
      )

      // Hero Futuristic Card
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
              Brush.linearGradient(
                listOf(accentSecondary.copy(alpha = 0.24f), CYBER_CARD, accentPrimary.copy(alpha = 0.16f))
              )
            )
            .border(1.5.dp, Brush.linearGradient(listOf(accentPrimary, accentSecondary)), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          PremiumIconBadge(
            icon = Icons.Filled.PlayArrow,
            contentDescription = title,
            colors = listOf(accentPrimary, accentSecondary),
            sizeDp = 40,
          )
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(subtitle, color = Color(0xFFC9D4F0), fontSize = 11.5.sp)
          }
          Box(
            modifier =
              Modifier
                .background(accentPrimary.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
                .border(1.dp, accentPrimary.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
          ) {
            Text(badgeLabel, color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
          }
        }
        Text(statusNote, color = Color(0xFFE0E7FF), fontSize = 12.sp, lineHeight = 17.sp)
      }

      // Honest Provider Status Strip (BYO Keys vs Open Free Sources)
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("seo_provider_registry_strip"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        providers.forEach { p ->
          val chipColor = if (p.isConfigured) CYBER_GREEN else Color(0xFF94A3B8)
          Box(
            modifier =
              Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(chipColor.copy(alpha = 0.14f))
                .border(1.dp, chipColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
          ) {
            Text(
              text = "${p.displayName.substringBefore(" (")}: ${if (p.isConfigured) "Active" else "Not configured"}",
              color = chipColor,
              fontSize = 10.sp,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }

      handoff?.let { h ->
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .testTag("seo_handoff_banner")
              .clip(RoundedCornerShape(14.dp))
              .background(Color(0xFF101F3A))
              .border(1.dp, CYBER_CYAN.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
              .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            "🎬 ভিডিও স্টুডিও থেকে প্রস্তুত: ${h.projectTitle}",
            color = CYBER_CYAN,
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp,
          )
          Text(
            "${h.aspectRatioLabel} (${h.actualDurationSec}s) • ${h.publicFolderLabel}",
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp,
          )
          Button(
            onClick = {
              topic = h.projectTitle
              val summaryScenes = h.sceneHeadlines.take(3).joinToString(", ")
              audienceGoal = "${h.aspectRatioLabel} (${h.actualDurationSec}s)${if (summaryScenes.isNotBlank()) " • $summaryScenes" else ""}"
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("seo_import_handoff_btn"),
            colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN, contentColor = Color.Black),
          ) {
            Text("ভিডিও থেকে ইম্পোর্ট করুন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }
        }
      }

      val fieldColors =
        OutlinedTextFieldDefaults.colors(
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          focusedBorderColor = accentPrimary,
          unfocusedBorderColor = Color(0xFF475073),
          focusedLabelColor = accentPrimary,
          unfocusedLabelColor = Color(0xFFAAB4D0),
        )

      OutlinedTextField(
        value = topic,
        onValueChange = { topic = it },
        modifier = Modifier.fillMaxWidth().testTag("seo_topic_input"),
        label = { Text("ভিডিওর বিষয় / টপিক লিখুন (যেমন: 3D Cartoon Story Bangla)") },
        singleLine = true,
        colors = fieldColors,
      )

      OutlinedTextField(
        value = audienceGoal,
        onValueChange = { audienceGoal = it },
        modifier = Modifier.fillMaxWidth().testTag("seo_audience_input"),
        label = { Text("দর্শক / লক্ষ্য") },
        singleLine = true,
        colors = fieldColors,
      )

      Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
          "বাংলা ও ইংরেজি (Bilingual)",
          "শুধু বাংলা",
          "English Global",
        ).forEach { lang ->
          FilterChip(
            selected = languageLabel == lang,
            onClick = { languageLabel = lang },
            label = { Text(lang, fontSize = 11.sp) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentPrimary, selectedLabelColor = Color.White),
          )
        }
      }

      Button(
        onClick = { runSeoGeneration() },
        enabled = !loading,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("seo_generate_btn"),
        colors = ButtonDefaults.buttonColors(containerColor = accentPrimary, contentColor = Color.White),
      ) {
        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          if (loading) "রিসার্চ ও SEO তৈরি হচ্ছে…" else "SEO তৈরি ও রিসার্চ করুন",
          fontWeight = FontWeight.Bold,
        )
      }

      if (loading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = accentPrimary)
      }

      if (exportMessage.isNotBlank()) {
        Text(exportMessage, color = CYBER_CYAN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }

      val pkg = seoResult
      if (pkg != null) {
        SeoResultCard(
          title = "SEO শিরোনামসমূহ (${pkg.titles.size}টি)",
          icon = Icons.Filled.AutoGraph,
          accent = accentPrimary,
          body = pkg.titles.mapIndexed { idx, t -> "${idx + 1}. $t" }.joinToString("\n"),
          onCopy = { copyBlock("Titles", pkg.titles.joinToString("\n")) },
        )

        SeoResultCard(
          title = "থাম্বনেইল হুক ও প্রথম ৩০ সেকেন্ডের স্ক্রিপ্ট",
          icon = Icons.Filled.SmartDisplay,
          accent = CYBER_CYAN,
          body = "থাম্বনেইল টেক্সট: ${pkg.thumbnailTextIdea}\n\nভিডিও হুক:\n${pkg.scriptHookBn}",
          onCopy = { copyBlock("Hook & Thumbnail", "${pkg.thumbnailTextIdea}\n${pkg.scriptHookBn}") },
        )

        SeoResultCard(
          title = "সার্চ কিওয়ার্ড ও হ্যাশট্যাগ",
          icon = Icons.Filled.Tag,
          accent = CYBER_PURPLE,
          body = "Keywords:\n${pkg.keywords.joinToString(", ")}\n\nHashtags:\n${pkg.hashtags.joinToString(" ")}",
          onCopy = { copyBlock("Keywords & Hashtags", "${pkg.keywords.joinToString(", ")}\n${pkg.hashtags.joinToString(" ")}") },
        )

        SeoResultCard(
          title = "ভিডিও ডেসক্রিপশন",
          icon = Icons.Filled.Subtitles,
          accent = CYBER_BLUE,
          body = pkg.description,
          onCopy = { copyBlock("Description", pkg.description) },
        )

        SeoResultCard(
          title = "ক্রিয়েটর SEO চেকলিস্ট",
          icon = Icons.Filled.Layers,
          accent = CYBER_GREEN,
          body = pkg.checklist.joinToString("\n") { "✓ $it" },
          onCopy = { copyBlock("Checklist", pkg.checklist.joinToString("\n")) },
        )

        if (pkg.sources.isNotEmpty()) {
          SeoResultCard(
            title = "যাচাইকৃত রিসার্চ সোর্স (${pkg.sources.size}টি)",
            icon = Icons.Filled.Search,
            accent = CYBER_AMBER,
            body = pkg.sources.joinToString("\n") { "• [${it.engine}] ${it.title}\n  ${it.url}" },
            onCopy = { copyBlock("Sources", pkg.sources.joinToString("\n") { "${it.title}: ${it.url}" }) },
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(
            onClick = {
              scope.launch {
                val res =
                  withContext(Dispatchers.IO) {
                    LxrDownloadStorage.saveTextDeliverable(
                      context = ctx,
                      category = LxrStorageCategory.YOUTUBE_SEO,
                      projectSlug = pkg.topic,
                      fileName = "youtube_seo.md",
                      content = pkg.toExportMarkdown(),
                    )
                  }
                exportMessage =
                  res.fold(
                    onSuccess = { f -> "সেভ হয়েছে ✓: ${f.absolutePath}" },
                    onFailure = { e -> "সেভ ব্যর্থ: ${e.message}" },
                  )
              }
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("seo_export_btn"),
            colors = ButtonDefaults.buttonColors(containerColor = CYBER_CYAN, contentColor = Color.Black),
          ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("LxR ফোল্ডারে সেভ", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
          OutlinedButton(
            onClick = { onNavigate("video_creator") },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("seo_send_to_video_btn"),
          ) {
            Icon(Icons.Filled.Movie, contentDescription = null, tint = accentPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("ভিডিও স্টুডিও", color = Color.White, fontSize = 11.5.sp, maxLines = 1)
          }
        }
      }
    }

    // Pinned outside the scroll container so `seo_studio_screen_back_btn` is always immediately accessible
    OutlinedButton(
      onClick = back,
      modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(min = 48.dp).testTag("seo_studio_screen_back_btn"),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = null,
        tint = accentPrimary,
        modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(text = backHomeLabel, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun SeoResultCard(
  title: String,
  icon: ImageVector,
  accent: Color,
  body: String,
  onCopy: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(CYBER_CARD)
        .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
      IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.ContentCopy, contentDescription = "কপি করুন", tint = accent, modifier = Modifier.size(18.dp))
      }
    }
    Text(body, color = Color(0xFFDCE6FF), fontSize = 12.sp, lineHeight = 18.sp)
  }
}
