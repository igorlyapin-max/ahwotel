package com.ahwotel

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider

/** Only in the isolated acceptance APK. Holds the document-picker result across recreation. */
class ExportRecreationActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    val app=application as MonitorApp
                    when(intent.getStringExtra("kind")) {
                        "oem" -> OemScreen(app) { }
                        "self" -> AgentTelemetryScreen(app,false) { }
                        else -> AgentTelemetryScreen(app,true) { }
                    }
                }
            }
        }
    }
    companion object {
        var requestCode: Int?=null
        val registry=object: ActivityResultRegistry() {
            override fun <I,O> onLaunch(code: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?) {
                requestCode=code
            }
        }
        private val owner=object: ActivityResultRegistryOwner { override val activityResultRegistry=registry }
    }
}
