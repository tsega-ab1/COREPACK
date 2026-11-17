package com.corepack.pet.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
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
import com.corepack.pet.Prefs
import com.corepack.pet.PetAgent
import com.corepack.pet.PetModules
import com.corepack.pet.Requirements
import com.corepack.pet.agent.Capability
import com.corepack.pet.agent.Hue
import com.corepack.pet.agent.HttpToolGateway
import com.corepack.pet.agent.RemoteToolInfo
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun tintFor(h: Hue): Tint = when (h) {
    Hue.VIOLET -> Tints.Violet
    Hue.ROSE -> Tints.Rose
    Hue.MINT -> Tints.Mint
    Hue.SKY -> Tints.Sky
    Hue.PEACH -> Tints.Peach
}

/** What the pet may do. The model only ever sees the tools that are switched on. */
@Composable
fun ToolsScreen(tick: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var caps by remember(tick) { mutableStateOf(PetStores.capabilities(ctx)) }
    // What Android has to allow for each capability, straight from the modules.
    val needs = remember(tick) {
        PetModules.builtin(ctx, PetAgent.facts(ctx)).mapNotNull { m -> m.capability?.let { it to m.requirements } }.toMap()
    }
    var pcTools by remember(tick) { mutableStateOf<List<RemoteToolInfo>?>(null) }
    var pcStatus by remember(tick) { mutableStateOf<String?>("Asking your PC for its tools") }
    LaunchedEffect(tick) {
        val result = withContext(Dispatchers.IO) {
            runCatching { HttpToolGateway({ Prefs.baseUrl(ctx) }, { Prefs.token(ctx) }).list() }
        }
        result.onSuccess {
            pcTools = it
            pcStatus = null
        }.onFailure {
            pcStatus = if (it is SocketTimeoutException) "Your PC is still starting its tools. Open this screen again in a moment." else it.message ?: "Could not reach your PC"
        }
    }

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
                val tint = tintFor(cap.hue)
                Row(
                    Modifier.fillMaxWidth().alpha(if (on) 1f else 0.7f).tinted(tint, RoundedCornerShape(30.dp)).padding(horizontal = 21.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 13.dp)) {
                        Txt(cap.label, tint.ink, 16.sp, weight = FontWeight.SemiBold)
                        Txt(cap.blurb, tint.ink.copy(alpha = 0.85f), 13.sp, Modifier.padding(top = 2.dp))
                        for (r in needs[cap].orEmpty()) {
                            val allowed = remember(tick) { Requirements.isGranted(ctx, r) }
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Txt(if (allowed) "${r.label}: allowed" else "${r.label}: needed", tint.ink, 12.sp, weight = FontWeight.SemiBold)
                                if (!allowed) {
                                    Box(
                                        Modifier.height(40.dp).glass(RoundedCornerShape(20.dp))
                                            .clickable(onClickLabel = "Grant ${r.label}", role = Role.Button) { ctx.startActivity(Requirements.grantIntent(ctx, r)) }
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { Txt("Grant", Ink, 12.sp, weight = FontWeight.SemiBold) }
                                }
                            }
                        }
                    }
                    Toggle(on, tint, cap.label) { value ->
                        PetStores.setCapability(ctx, cap, value)
                        caps = PetStores.capabilities(ctx)
                    }
                }
            }

            // What the gateway on the PC offers. The pet can use them once "Online tools" is switched on above.
            item { Txt("From your PC", Muted, 13.sp, Modifier.padding(start = 4.dp, top = 10.dp), weight = FontWeight.SemiBold) }
            pcStatus?.let { status -> item { EmptyCard(status) } }
            val offered = pcTools
            if (offered != null) {
                if (offered.isEmpty()) {
                    item {
                        EmptyCard(
                            "Your PC offers no tools yet. To add web search, run this on the PC, then restart the gateway: " +
                                "corepack gateway mcp add --confirm never --allow search,fetch_content,expand_link web -- uvx duckduckgo-mcp-server"
                        )
                    }
                }
                val groups = offered.groupBy { it.server }.entries.toList()
                items(groups, key = { "pc:" + it.key }) { group ->
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(horizontal = 21.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(group.key, Ink, 15.sp, weight = FontWeight.SemiBold)
                        for (t in group.value) {
                            Column {
                                Txt(t.name.removePrefix(group.key + "__"), Ink, 13.sp, weight = FontWeight.SemiBold)
                                Txt(t.description, Muted, 13.sp, maxLines = 2)
                                if (t.needsConfirm) Txt("Asks you in the chat before it runs", Muted, 12.sp)
                            }
                        }
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
