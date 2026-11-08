package com.corepack.pet.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.corepack.pet.OverlayService
import com.corepack.pet.PetStores
import com.corepack.pet.PetView
import com.corepack.pet.agent.Capability
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Short, readable time for a reminder: "15:00" today, "Sun 10:00" otherwise. */
fun clockLabel(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(ms).atZone(zone)
    val pattern = if (at.toLocalDate() == LocalDate.now(zone)) "HH:mm" else "EEE HH:mm"
    return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(at)
}

@Composable
fun Txt(text: String, color: Color, size: TextUnit, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal, serif: Boolean = false, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    BasicText(
        text,
        modifier,
        TextStyle(
            color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.25f,
            fontFamily = if (serif) Serif else null, textAlign = align ?: TextAlign.Unspecified,
        ),
        maxLines = maxLines,
    )
}

/** Fades and slides a card in, a little later for each [index]. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(70L * index)
        shown = true
    }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(420), label = "enter")
    return this.graphicsLayer {
        alpha = a
        translationY = (1f - a) * 40f
    }
}

@Composable
fun AbilityCard(modifier: Modifier, tint: Tint, label: String, headline: String, size: TextUnit, sub: String?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "press")
    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tinted(tint, RoundedCornerShape(30.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 21.dp, vertical = 16.dp),
    ) {
        Txt(label, tint.ink, 13.sp, weight = FontWeight.SemiBold)
        Txt(headline, tint.ink, size, Modifier.padding(top = 4.dp), serif = true)
        if (sub != null) Txt(sub, tint.ink.copy(alpha = 0.85f), 13.sp, Modifier.padding(top = 2.dp))
    }
}

@Composable
fun HomeScreen(tick: Int, connected: Boolean?, onOpen: (Screen, Int) -> Unit, onAsk: () -> Unit, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    val next = remember(tick) { PetStores.reminders(ctx).active().firstOrNull() }
    val openTasks = remember(tick) { PetStores.tasks(ctx).list(false).size }
    val notes = remember(tick) { PetStores.notes(ctx).recent(300).size }
    val caps = remember(tick) { PetStores.capabilities(ctx) }
    val scope = rememberCoroutineScope()
    var petOn by remember(tick) { mutableStateOf(OverlayService.running) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val chipShape = RoundedCornerShape(22.dp)
            Box(
                Modifier.height(44.dp).glass(chipShape)
                    .clickable(onClickLabel = if (petOn) "Hide the pet" else "Show the pet", role = Role.Button) {
                        if (petOn) {
                            ctx.startService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
                        } else if (!Settings.canDrawOverlays(ctx)) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } else {
                            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
                        }
                        scope.launch {
                            delay(600)
                            petOn = OverlayService.running
                        }
                    }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(if (petOn) "Hide pet" else "Show pet", Ink, 13.sp, weight = FontWeight.SemiBold) }
            Box(Modifier.padding(start = 8.dp)) { GlassButton("Connection and permissions", onConnect) { TuneIcon() } }
        }

        Column(Modifier.fillMaxWidth().height(262.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AndroidView(factory = { PetView(it) }, modifier = Modifier.size(212.dp, 187.dp))
            Txt(
                if (connected == true) "Ready when you are" else "Waiting for your PC", Ink, 21.sp, serif = true, align = TextAlign.Center,
            )
            Txt(
                when (connected) {
                    true -> "Connected to your PC"
                    false -> "Not connected. Tap to set up."
                    null -> "Checking your PC"
                },
                Muted, 13.sp, Modifier.clickable(enabled = connected != true, onClick = onConnect).padding(vertical = 4.dp), align = TextAlign.Center,
            )
        }

        val sheet = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
        Box(Modifier.fillMaxWidth().weight(1f).glass(sheet)) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(55.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.copy(alpha = 0.18f)))
            Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 21.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(233.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        AbilityCard(Modifier.weight(144f).fillMaxHeight().enter(0), Tints.Violet, "Ask", "Ask me anything", 21.sp, "Weather, facts, reminders and phone actions", onAsk)
                        Column(Modifier.weight(191f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            AbilityCard(
                                Modifier.weight(1f).fillMaxWidth().enter(1), Tints.Rose, "Reminders",
                                next?.let { clockLabel(it.dueMs) } ?: "None", 34.sp, next?.text ?: "Ask the pet to set one",
                            ) { onOpen(Screen.Library, 0) }
                            AbilityCard(
                                Modifier.weight(1f).fillMaxWidth().enter(2), Tints.Mint, "Tasks and notes",
                                "$openTasks open", 34.sp, "$notes notes saved",
                            ) { onOpen(Screen.Library, 1) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(89.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        AbilityCard(
                            Modifier.weight(1f).fillMaxHeight().enter(3), Tints.Sky, "Phone actions",
                            if (Capability.PHONE_ACTIONS in caps) "On" else "Off", 21.sp, null,
                        ) { onOpen(Screen.Tools, 0) }
                        AbilityCard(
                            Modifier.weight(1f).fillMaxHeight().enter(4), Tints.Peach, "Tools",
                            "${caps.size} of ${Capability.entries.size} on", 21.sp, null,
                        ) { onOpen(Screen.Tools, 0) }
                    }
                }
                AskBar(onAsk, Modifier.padding(horizontal = 21.dp, vertical = 13.dp))
            }
        }
    }
}

@Composable
fun AskBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(55.dp).glass(RoundedCornerShape(28.dp))
            .clickable(onClickLabel = "Open the chat", role = Role.Button, onClick = onClick)
            .padding(start = 21.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt("Ask anything", Muted, 14.sp, Modifier.weight(1f))
        Box(
            Modifier.size(39.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Tints.Violet.from, Tints.Violet.to))),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(16.dp)) {
                val w = size.width
                val c = Ink
                drawLine(c, Offset(w * 0.1f, w * 0.5f), Offset(w * 0.9f, w * 0.5f), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
                drawLine(c, Offset(w * 0.55f, w * 0.15f), Offset(w * 0.9f, w * 0.5f), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
                drawLine(c, Offset(w * 0.55f, w * 0.85f), Offset(w * 0.9f, w * 0.5f), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
