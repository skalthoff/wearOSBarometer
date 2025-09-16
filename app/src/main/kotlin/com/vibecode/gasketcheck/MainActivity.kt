package com.vibecode.gasketcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vibecode.gasketcheck.ui.AppTheme
import com.vibecode.gasketcheck.testflow.TestFlowEntry

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                TestFlowEntry()
            }
        }
    }
}

// Previews can be added later in module-specific preview files if needed.
