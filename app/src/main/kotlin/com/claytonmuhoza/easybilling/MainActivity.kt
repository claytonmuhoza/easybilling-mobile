package com.claytonmuhoza.easybilling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.claytonmuhoza.easybilling.core.designsystem.theme.EasyBillingTheme
import com.claytonmuhoza.easybilling.navigation.EasyBillingNavHost
import dagger.hilt.android.AndroidEntryPoint

/** Single activity of the application: every screen is a destination of its navigation graph. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EasyBillingTheme {
                EasyBillingNavHost()
            }
        }
    }
}
