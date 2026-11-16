package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

      // 3. YouTube ভিডিও SEO — Purple + Magenta Glow
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

  val glowIntensity by animateFloatAsState(
    targetValue =
      when {
        isPressed -> 1.35f
        isHovered -> 1.18f
        else -> 1f
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
  BackHandler(onBack = back)
  StudioScaffoldScreen(
    screenTag = "video_creator_screen",
    title = stringResource(R.string.video_creator_screen_title),
    subtitle = stringResource(R.string.video_creator_screen_subtitle),
    statusNote = stringResource(R.string.video_creator_status_note),
    topBarIcon = Icons.Filled.Videocam,
    accentPrimary = CYBER_CYAN,
    accentSecondary = CYBER_BLUE,
    modules =
      listOf(
        StudioModulePreview(
          icon = Icons.Filled.MovieCreation,
          title = "Scene Timeline",
          subtitle = "৯:১৬ শর্টস এবং ১৬:৯ ইউটিউব ফ্রেম লেআউট স্টুডিও",
        ),
        StudioModulePreview(
          icon = Icons.Filled.Subtitles,
          title = "Voice & Subtitle Sync",
          subtitle = "বাংলা ভয়েস ওভার ও টাইপোগ্রাফি ক্যাপশন স্টাইল প্রিভিউ",
        ),
        StudioModulePreview(
          icon = Icons.Filled.SmartDisplay,
          title = "Export Presets",
          subtitle = "HD / Full HD রেজোলিউশন এবং অডিও ট্র্যাক সেটআপ",
        ),
      ),
    back = back,
    onNavigate = onNavigate,
  )
}

@Composable
fun SeoStudioScreen(back: () -> Unit, onNavigate: (String) -> Unit) {
  BackHandler(onBack = back)
  StudioScaffoldScreen(
    screenTag = "seo_studio_screen",
    title = stringResource(R.string.seo_studio_screen_title),
    subtitle = stringResource(R.string.seo_studio_screen_subtitle),
    statusNote = stringResource(R.string.seo_studio_status_note),
    topBarIcon = Icons.Filled.PlayArrow,
    accentPrimary = Color(0xFFFF179E),
    accentSecondary = CYBER_PURPLE,
    modules =
      listOf(
        StudioModulePreview(
          icon = Icons.Filled.Search,
          title = "Title & Keyword Hub",
          subtitle = "বাংলা ও ইংরেজি YouTube সার্চ টাইটেল এবং কিওয়ার্ড সেকশন",
        ),
        StudioModulePreview(
          icon = Icons.Filled.Tag,
          title = "Tags & Description",
          subtitle = "ভাইরাল হ্যাশট্যাগ, ভিডিও ডেসক্রিপশন এবং চ্যাপ্টার লেআউট",
        ),
        StudioModulePreview(
          icon = Icons.Filled.AutoGraph,
          title = "SEO Score Preview",
          subtitle = "রিচ, সিটিআর এবং থাম্বনেইল পারফরম্যান্স ইনডিকেটর কাঠামো",
        ),
      ),
    back = back,
    onNavigate = onNavigate,
  )
}

private data class StudioModulePreview(
  val icon: ImageVector,
  val title: String,
  val subtitle: String,
)

@Composable
private fun StudioScaffoldScreen(
  screenTag: String,
  title: String,
  subtitle: String,
  statusNote: String,
  topBarIcon: ImageVector,
  accentPrimary: Color,
  accentSecondary: Color,
  modules: List<StudioModulePreview>,
  back: () -> Unit,
  onNavigate: (String) -> Unit,
) {
  var selectedIndex by remember { mutableIntStateOf(0) }
  val badgeLabel = stringResource(R.string.studio_ui_only_badge)
  val backHomeLabel = stringResource(R.string.back_to_home_label)

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .testTag(screenTag)
        .background(FULL_BG_GRADIENT)
        .statusBarsPadding()
        .navigationBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Column(
      modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      SubPageTopBar(
        title = title,
        icon = topBarIcon,
        iconColors = listOf(accentPrimary, accentSecondary),
        onBack = back,
        onNavigate = onNavigate,
      )

      // Hero Futuristic Card
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
              Brush.linearGradient(
                listOf(accentSecondary.copy(alpha = 0.24f), CYBER_CARD, accentPrimary.copy(alpha = 0.16f))
              )
            )
            .border(
              1.6.dp,
              Brush.linearGradient(listOf(accentPrimary, accentSecondary)),
              RoundedCornerShape(20.dp),
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          PremiumIconBadge(
            icon = topBarIcon,
            contentDescription = title,
            colors = listOf(accentPrimary, accentSecondary),
            sizeDp = 44,
          )
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(
              text = title,
              color = Color.White,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 18.sp,
            )
            Text(
              text = subtitle,
              color = Color(0xFFC9D4F0),
              fontSize = 12.sp,
            )
          }
        }

        Box(
          modifier =
            Modifier
              .background(accentPrimary.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
              .border(1.dp, accentPrimary.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
              .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
          Text(
            text = badgeLabel,
            color = Color.White,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
          )
        }

        Text(
          text = statusNote,
          color = Color(0xFFE0E7FF),
          fontSize = 13.sp,
          lineHeight = 19.sp,
        )
      }

      // Preview Module Cards (UI selection only — no backend or AI calls)
      modules.forEachIndexed { idx, item ->
        val selected = idx == selectedIndex
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 68.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(if (selected) accentPrimary.copy(alpha = 0.16f) else CYBER_CARD)
              .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accentPrimary else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(16.dp),
              )
              .clickable { selectedIndex = idx }
              .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          PremiumIconBadge(
            icon = item.icon,
            contentDescription = item.title,
            colors = if (selected) listOf(accentPrimary, accentSecondary) else listOf(Color(0xFF334155), Color(0xFF1E293B)),
            sizeDp = 38,
          )
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = item.title,
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp,
            )
            Text(
              text = item.subtitle,
              color = Color(0xFFAAB4D0),
              fontSize = 12.sp,
              lineHeight = 17.sp,
            )
          }
        }
      }

      Spacer(Modifier.height(4.dp))

      OutlinedButton(
        onClick = back,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${screenTag}_back_btn"),
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
}
