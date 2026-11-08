package com.corepack.pet.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corepack.pet.PetStores

private val TABS = listOf("Reminders", "Tasks", "Notes")

/** Reminders, open tasks and notes, kept on this phone. */
@Composable
fun LibraryScreen(tick: Int, initialTab: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(initialTab) }
    var version by remember { mutableIntStateOf(0) }
    // Read the stores here, in the composable body: `remember` cannot be called inside LazyColumn's content block.
    val reminders = remember(tick, version) { PetStores.reminders(ctx).active() }
    val tasks = remember(tick, version) { PetStores.tasks(ctx).list(false) }
    val notes = remember(tick, version) { PetStores.notes(ctx).recent(100) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Back", onBack) { BackIcon() }
            TABS.forEachIndexed { i, name ->
                val shape = RoundedCornerShape(22.dp)
                val base = Modifier.height(44.dp)
                Box(
                    (if (i == tab) base.tinted(Tints.Violet, shape) else base.glass(shape))
                        .clickable(onClickLabel = "Show $name", role = Role.Tab) { tab = i }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Txt(name, if (i == tab) Tints.Violet.ink else Ink, 13.sp, weight = FontWeight.SemiBold) }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(21.dp, 8.dp, 21.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                0 -> {
                    if (reminders.isEmpty()) item { EmptyCard("No reminders waiting. Ask the pet, for example: remind me in 2 minutes to drink water.") }
                    items(reminders, key = { it.id }) { r ->
                        Column(Modifier.fillMaxWidth().tinted(Tints.Rose, RoundedCornerShape(30.dp)).padding(horizontal = 21.dp, vertical = 16.dp)) {
                            Txt(clockLabel(r.dueMs), Tints.Rose.ink, 21.sp, serif = true)
                            Txt(r.text, Tints.Rose.ink, 15.sp, Modifier.padding(top = 2.dp))
                        }
                    }
                }
                1 -> {
                    if (tasks.isEmpty()) item { EmptyCard("No open tasks. Ask the pet to add one, for example: add buy bread to my tasks.") }
                    items(tasks, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().tinted(Tints.Mint, RoundedCornerShape(30.dp)).padding(horizontal = 21.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f))
                                    .clickable(onClickLabel = "Mark done", role = Role.Button) {
                                        PetStores.tasks(ctx).complete(t.id)
                                        version++
                                    },
                            )
                            Column(Modifier.padding(start = 16.dp)) {
                                Txt(t.text, Tints.Mint.ink, 15.sp, weight = FontWeight.SemiBold)
                                if (t.due.isNotBlank()) Txt("Due ${t.due}", Tints.Mint.ink.copy(alpha = 0.85f), 13.sp)
                            }
                        }
                    }
                }
                else -> {
                    if (notes.isEmpty()) item { EmptyCard("No notes yet. Ask the pet to write one down, for example: note that the pharmacy opens at 8.") }
                    items(notes, key = { it.id }) { n ->
                        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(horizontal = 21.dp, vertical = 16.dp)) {
                            Txt(n.title, Ink, 15.sp, weight = FontWeight.SemiBold)
                            if (n.text.isNotBlank()) Txt(n.text, Muted, 13.sp, Modifier.padding(top = 2.dp), maxLines = 4)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyCard(text: String) {
    Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(21.dp)) { Txt(text, Muted, 14.sp) }
}
