package com.corepack.pet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.corepack.pet.ui.PetApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The home screen. Cards on it open the chat, the library, and the tools list. */
class HomeActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var connected by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(tick) {
                while (true) {
                    connected = withContext(Dispatchers.IO) {
                        Prefs.token(this@HomeActivity).isNotEmpty() && EngineClient.health(Prefs.baseUrl(this@HomeActivity))
                    }
                    delay(10_000)
                }
            }
            PetApp(
                tick = tick,
                connected = connected,
                onAsk = { startActivity(Intent(this, ChatActivity::class.java)) },
                onConnect = { startActivity(Intent(this, MainActivity::class.java)) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }
}
