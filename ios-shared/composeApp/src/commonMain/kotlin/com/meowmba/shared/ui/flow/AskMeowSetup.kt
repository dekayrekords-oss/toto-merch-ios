package com.meowmba.shared.ui.flow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Android AskMeowSetup adds a Quick Settings tile that captures the screen once. iOS lets no app
 * capture another app's screen, so the same job is a screenshot handed to the "Ask Meow about
 * this screen" shortcut, which Back Tap or the Action Button can run from any app.
 */
@Composable
fun AskMeowSetup(modifier: Modifier = Modifier) {
    val c = LocalFlowColors.current
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Ask Meow in another app", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text("In Shortcuts, make a shortcut with “Take Screenshot” then “Ask Meow about this screen”. " +
            "Set it on Settings › Accessibility › Touch › Back Tap (double tap the back of the phone) or on the Action Button. " +
            "Meow reads the picture on the phone and answers; nothing is captured until you run it.",
            style = MaterialTheme.typography.bodyMedium, color = c.ink2)
        Text("You can also share a screenshot or photo to Meow with the + button, or ask Siri “Ask Meowmba”.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.ink2)
    }
}
