package com.meowmba.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.attach.Attachments
import com.meowmba.shared.core.flow.FlowThemes
import com.meowmba.shared.ui.flow.FlowColors
import com.meowmba.shared.ui.flow.FlowLink
import com.meowmba.shared.ui.flow.FlowType

/**
 * Ported from Android `ui/flow/AttachSheet.kt` (build 171): take a photo, choose a photo, or choose
 * any file. Whatever is picked is read on the phone and handed to Meow with the user's note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(onPick: (Attachments.Kind) -> Unit, onDismiss: () -> Unit) {
    val c = FlowColors.from(FlowThemes.palette(FlowThemes.DEFAULT_KEY, false))
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = null, containerColor = c.paper, shape = RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxWidth().background(c.paper).padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 30.dp)) {
            BasicText("Show Meow something", style = FlowType.headline.copy(fontSize = 24.sp, lineHeight = 30.sp, color = c.ink))
            BasicText("A receipt, a label, a document. Meow reads it on this phone.", Modifier.padding(top = 6.dp),
                style = FlowType.because.copy(color = c.ink2))
            @Composable fun Row(label: String, onClick: () -> Unit) = BasicText(label,
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
                style = FlowType.itemTitle.copy(color = c.ink))
            Spacer(Modifier.height(12.dp))
            Row("Take a photo") { onPick(Attachments.Kind.PHOTO) }
            Row("Choose a photo") { onPick(Attachments.Kind.IMAGE) }
            Row("Choose a file or document") { onPick(Attachments.Kind.FILE) }
            Spacer(Modifier.height(8.dp))
            FlowLink("Cancel", c.ink2, onDismiss)
        }
    }
}
