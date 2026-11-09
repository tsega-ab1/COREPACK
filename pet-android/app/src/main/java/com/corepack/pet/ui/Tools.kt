package com.corepack.pet.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corepack.pet.PetStores
import com.corepack.pet.agent.Capability

private fun tintFor(c: Capability): Tint = when (c) {
    Capability.REMINDERS -> Tints.Rose
    Capability.TASKS_NOTES -> Tints.Mint
    Capability.PHONE_ACTIONS -> Tints.Sky
    Capability.LOOKUPS -> Tints.Violet
    Capability.NUDGES -> Tints.Peach
    Capability.REMOTE_TOOLS -> Tints.Sky
}

/** What the pet may do. The model only ever sees the tools that are switched on. */
@Composable
fun ToolsScreen(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var caps by remember(tick) { mutableStateOf(PetStores.capabilities(ctx)) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            GlassButton("Back", onBack) { BackIcon() }
            Txt("What the pet may do. The model only sees the tools that are switched on.", Muted, 13.sp, Modifier.weight(1f))
        }
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(21.dp, 8.dp, 21.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(Capability.entries.toList(), key = { it.name }) { cap ->
                val on = cap in caps
                val tint = tintFor(cap)
                Row(
                    Modifier.fillMaxWidth().alpha(if (on) 1f else 0.7f).tinted(tint, RoundedCornerShape(30.dp)).padding(horizontal = 21.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 13.dp)) {
                        Txt(cap.label, tint.ink, 16.sp, weight = FontWeight.SemiBold)
                        Txt(cap.blurb, tint.ink.copy(alpha = 0.85f), 13.sp, Modifier.padding(top = 2.dp))
                    }
                    Toggle(on, tint, cap.label) { value ->
                        PetStores.setCapability(ctx, cap, value)
                        caps = PetStores.capabilities(ctx)
                    }
                }
            }
        }
    }
}

/** A sliding switch; 52 x 32 dp, with the knob springing across. */
@Composable
private fun Toggle(on: Boolean, tint: Tint, label: String, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (on) tint.ink else Color.White.copy(alpha = 0.7f), label = "track")
    val knob by animateDpAsState(if (on) 22.dp else 4.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "knob")
    Box(
        Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(track)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = knob).size(24.dp).clip(CircleShape).background(if (on) Color.White else tint.ink.copy(alpha = 0.55f)))
    }
}
