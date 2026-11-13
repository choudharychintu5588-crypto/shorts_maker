package com.shortsmaker.proof

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi

@UnstableApi
class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        shared = sharedTextFrom(intent)
        setContent { ShortsMakerApp(shared) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedTextFrom(intent)?.let { shared = it }
    }

    private fun sharedTextFrom(i: Intent?): String? =
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
}
