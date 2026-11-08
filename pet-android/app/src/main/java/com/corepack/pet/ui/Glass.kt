package com.corepack.pet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val Ink = Color(0xFF171A26)
val Muted = Color(0xFF4F5368)
val Paper = Color(0xFFFBFBFE)
val Serif = FontFamily.Serif

/** One gradient colour family; [ink] is the text colour that reads well on it. */
class Tint(val from: Color, val to: Color, val ink: Color)

object Tints {
    val Violet = Tint(Color(0xFFCDBBFF), Color(0xFF8FB4FF), Color(0xFF1B1240))
    val Rose = Tint(Color(0xFFFFDDAE), Color(0xFFFF9EB5), Color(0xFF4A1A28))
    val Mint = Tint(Color(0xFFB8F2D8), Color(0xFF6FD6C5), Color(0xFF0B3B34))
    val Sky = Tint(Color(0xFFBBDDFF), Color(0xFF8D9BFF), Color(0xFF101A4D))
    val Peach = Tint(Color(0xFFFFD3BF), Color(0xFFFFA58F), Color(0xFF4A1D12))
}

private val ShadowColor = Color(0x33505090)

/** A coloured gradient card with a soft highlight in its top-left corner. */
fun Modifier.tinted(t: Tint, shape: Shape): Modifier = this
    .shadow(12.dp, shape, ambientColor = ShadowColor, spotColor = ShadowColor)
    .clip(shape)
    .background(Brush.linearGradient(listOf(t.from, t.to)))
    .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center = Offset(0f, 0f), radius = 700f))
    .border(1.dp, Color.White.copy(alpha = 0.8f), shape)

/** A frosted-glass panel: translucent white over the soft colour glows behind it. */
fun Modifier.glass(shape: Shape): Modifier = this
    .shadow(12.dp, shape, ambientColor = ShadowColor, spotColor = ShadowColor)
    .clip(shape)
    .background(Color.White.copy(alpha = 0.62f))
    .border(1.dp, Color.White.copy(alpha = 0.95f), shape)

/** The soft colour glows behind everything. Plain radial gradients, so they cost almost nothing. */
@Composable
fun Blobs() {
    Box(Modifier.fillMaxSize()) {
        Blob(Color(0xFFCDBBFF), 300.dp, (-60).dp, (-40).dp, 0.7f)
        Blob(Color(0xFFFFD3B8), 260.dp, 200.dp, 140.dp, 0.6f)
        Blob(Color(0xFFBFDCFF), 300.dp, (-40).dp, 520.dp, 0.7f)
    }
}

@Composable
private fun Blob(color: Color, size: Dp, x: Dp, y: Dp, alpha: Float) {
    Box(
        Modifier
            .offset(x, y)
            .size(size)
            .background(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent)), CircleShape)
    )
}

/** A round glass button, 44 dp (the smallest comfortable touch target). */
@Composable
fun GlassButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .glass(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun BackIcon() {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.85f, w * 0.5f); lineTo(w * 0.15f, w * 0.5f)
            moveTo(w * 0.45f, w * 0.2f); lineTo(w * 0.15f, w * 0.5f); lineTo(w * 0.45f, w * 0.8f)
        }
        drawPath(p, Ink, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Two sliders: the settings icon. */
@Composable
fun TuneIcon() {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val st = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Ink, Offset(w * 0.1f, w * 0.3f), Offset(w * 0.9f, w * 0.3f), strokeWidth = st.width, cap = StrokeCap.Round)
        drawLine(Ink, Offset(w * 0.1f, w * 0.7f), Offset(w * 0.9f, w * 0.7f), strokeWidth = st.width, cap = StrokeCap.Round)
        drawCircle(Ink, radius = w * 0.14f, center = Offset(w * 0.65f, w * 0.3f))
        drawCircle(Ink, radius = w * 0.14f, center = Offset(w * 0.35f, w * 0.7f))
    }
}
