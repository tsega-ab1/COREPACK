package com.corepack.pet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class Screen { Home, Library, Tools }

/** Home is the solid base. The other screens open from its cards and slide back out. */
@Composable
fun PetApp(tick: Int, connected: Boolean?, onAsk: () -> Unit, onConnect: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.Home) }
    var libraryTab by remember { mutableIntStateOf(0) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Box(Modifier.fillMaxSize().background(Paper)) {
        Blobs()
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (targetState != Screen.Home) {
                    (slideInHorizontally(tween(320)) { it / 5 } + fadeIn(tween(320))) togetherWith fadeOut(tween(160))
                } else {
                    fadeIn(tween(320)) togetherWith (slideOutHorizontally(tween(320)) { it / 5 } + fadeOut(tween(220)))
                }
            },
            label = "screen",
        ) { s ->
            when (s) {
                Screen.Home -> HomeScreen(
                    tick, connected,
                    onOpen = { target, tab ->
                        libraryTab = tab
                        screen = target
                    },
                    onAsk = onAsk, onConnect = onConnect,
                )
                Screen.Library -> LibraryScreen(tick, libraryTab) { screen = Screen.Home }
                Screen.Tools -> ToolsScreen(tick) { screen = Screen.Home }
            }
        }
    }
}
