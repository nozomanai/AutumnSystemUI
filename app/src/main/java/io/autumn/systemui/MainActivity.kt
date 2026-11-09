package io.autumn.systemui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.autumn.systemui.ui.AutumnRoot
import io.autumn.systemui.ui.theme.AutumnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutumnTheme {
                AutumnRoot()
            }
        }
    }
}
