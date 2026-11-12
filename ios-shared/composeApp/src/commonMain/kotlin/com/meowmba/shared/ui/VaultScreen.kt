package com.meowmba.shared.ui

import com.meowmba.shared.ui.theme.Surface

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import com.meowmba.shared.ui.theme.AlertDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.vault.VaultNote
import com.meowmba.shared.vault.VaultRepository

/** Compiled once: the old code rebuilt these per markdown line per recomposition. */
private val VAULT_BULLET = Regex("^[\\-\\*] .+")
private val VAULT_HR = Regex("^---+$")
private val VAULT_INLINE = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`|\\[\\[(.+?)\\]\\]")

private val VAULT_BG: Color @Composable get() = MaterialTheme.colorScheme.background
private val PANEL_BG: Color @Composable get() = MaterialTheme.colorScheme.surface
private val ACCENT: Color @Composable get() = MaterialTheme.colorScheme.primary
private val MUTED: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val SURFACE: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val BORDER: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val GREEN: Color @Composable get() = MaterialTheme.colorScheme.primary
private val PURPLE: Color @Composable get() = MaterialTheme.colorScheme.secondary

/**
 * Ported from Android `ui/companion/VaultScreen.kt` (build 171); only date formatting differs.
 *
 * Obsidian-style vault screen. Three modes:
 *  - LIST: all notes, searchable, tap to open
 *  - VIEW: rendered Markdown of a single note, edit button
 *  - EDIT: raw Markdown editor for a note (new or existing)
 */
@Composable
internal fun VaultScreen(
    vault: VaultRepository,
    onClose: () -> Unit
) {
    var mode by remember { mutableStateOf(VaultMode.LIST) }
    var selectedNote by remember { mutableStateOf<VaultNote?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editBody by remember { mutableStateOf("") }
    var editTags by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(vault.list()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun refresh() { notes = vault.list() }

    fun openNote(note: VaultNote) {
        selectedNote = note
        mode = VaultMode.VIEW
    }

    fun startEdit(note: VaultNote?) {
        editTitle = note?.title ?: ""
        editBody = note?.body ?: ""
        editTags = note?.tags?.joinToString(", ") ?: ""
        selectedNote = note
        mode = VaultMode.EDIT
    }

    fun saveAndReturn() {
        if (editTitle.isNotBlank()) {
            val tags = editTags.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val saved = vault.save(editTitle, editBody, tags)
            refresh()
            selectedNote = saved
            mode = VaultMode.VIEW
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = VAULT_BG) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            // ── Header bar ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PANEL_BG)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mode != VaultMode.LIST) {
                    IconButton(onClick = {
                        mode = VaultMode.LIST
                        refresh()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Text(
                    text = when (mode) {
                        VaultMode.LIST -> "Vault"
                        VaultMode.VIEW -> selectedNote?.title ?: "Note"
                        VaultMode.EDIT -> if (selectedNote == null) "New Note" else "Edit"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                when (mode) {
                    VaultMode.LIST -> IconButton(onClick = { startEdit(null) }) {
                        Icon(Icons.Default.Add, contentDescription = "New note", tint = ACCENT)
                    }
                    VaultMode.VIEW -> {
                        IconButton(onClick = { startEdit(selectedNote) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = ACCENT)
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    VaultMode.EDIT -> IconButton(onClick = { saveAndReturn() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = GREEN)
                    }
                }

                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MUTED)
                }
            }

            // ── Main content ───────────────────────────────────────────────
            when (mode) {
                VaultMode.LIST -> NoteList(
                    notes = notes,
                    query = query,
                    vault = vault,
                    onQueryChange = { query = it },
                    onOpen = { openNote(it) },
                )
                VaultMode.VIEW -> NoteViewer(
                    note = selectedNote!!,
                    onWikiLinkClick = { title ->
                        val linked = vault.get(title)
                        if (linked != null) openNote(linked)
                        else startEdit(null).also { editTitle = title }
                    },
                )
                VaultMode.EDIT -> NoteEditor(
                    title = editTitle,
                    body = editBody,
                    tags = editTags,
                    onTitleChange = { editTitle = it },
                    onBodyChange = { editBody = it },
                    onTagsChange = { editTags = it },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            containerColor = PANEL_BG,
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete note?", color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("\"${selectedNote?.title}\" will be permanently deleted.", color = MUTED) },
            confirmButton = {
                TextButton(onClick = {
                    selectedNote?.let { vault.delete(it.title) }
                    refresh()
                    mode = VaultMode.LIST
                    confirmDelete = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = MUTED) }
            }
        )
    }
}

// ── List ─────────────────────────────────────────────────────────────────────

@Composable
private fun NoteList(
    notes: List<VaultNote>,
    query: String,
    vault: VaultRepository,
    onQueryChange: (String) -> Unit,
    onOpen: (VaultNote) -> Unit,
) {
    val displayed = remember(query, notes) {
        if (query.isBlank()) notes else vault.search(query)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SURFACE)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(ACCENT),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search notes…", color = MUTED, fontSize = 14.sp)
                    inner()
                }
            )
        }

        if (displayed.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "No notes yet.\nTap + to create your first note, or ask the cat."
                    else "No notes match \"$query\".",
                    color = MUTED,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(displayed) { note ->
                    NoteCard(note = note, onClick = { onOpen(note) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: VaultNote, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PANEL_BG)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(note.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (note.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                note.tags.take(4).forEach { tag ->
                    Text(
                        "#$tag",
                        color = PURPLE,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(PURPLE.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (note.preview.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(note.preview, color = MUTED, fontSize = 12.sp, maxLines = 2)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(com.meowmba.shared.memory.DateText.shortDate(note.modifiedAt, com.meowmba.shared.core.PlatformTime.utcOffsetMinutes()), color = BORDER, fontSize = 11.sp)
    }
}

// ── Viewer ────────────────────────────────────────────────────────────────────

@Composable
private fun NoteViewer(
    note: VaultNote,
    onWikiLinkClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        if (note.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                note.tags.forEach { tag ->
                    Text(
                        "#$tag", color = PURPLE, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(PURPLE.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        MarkdownBody(text = note.body, onWikiLink = onWikiLinkClick)
    }
}

// ── Editor ────────────────────────────────────────────────────────────────────

@Composable
private fun NoteEditor(
    title: String,
    body: String,
    tags: String,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        EditorField(value = title, onValueChange = onTitleChange, placeholder = "Note title…",
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold))
        HorizontalDivider(color = BORDER, modifier = Modifier.padding(vertical = 8.dp))

        EditorField(value = tags, onValueChange = onTagsChange, placeholder = "tags, comma-separated (optional)",
            textStyle = TextStyle(color = PURPLE, fontSize = 13.sp))
        HorizontalDivider(color = BORDER, modifier = Modifier.padding(vertical = 8.dp))

        EditorField(
            value = body,
            onValueChange = onBodyChange,
            placeholder = "Write in Markdown…\n\n# Heading\n**bold** *italic*\n[[wikilink]]",
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 400.dp)
        )
    }
}

@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        cursorBrush = SolidColor(ACCENT),
        modifier = modifier,
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = MUTED, style = textStyle)
            inner()
        }
    )
}

// ── Markdown renderer ─────────────────────────────────────────────────────────

/**
 * Lightweight Markdown→Compose renderer. Handles:
 *  - # / ## / ### headings
 *  - **bold** and *italic*
 *  - [[wikilinks]] (tappable)
 *  - `inline code` and fenced ```code blocks```
 *  - - / * bullet lists
 *  - > blockquotes
 *  - blank lines as paragraph breaks
 */
@Composable
private fun MarkdownBody(text: String, onWikiLink: (String) -> Unit = {}) {
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            // Fenced code block
            line.trimStart().startsWith("```") -> {
                val lang = line.trim().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                CodeBlock(code = codeLines.joinToString("\n"), lang = lang.ifBlank { null })
                Spacer(modifier = Modifier.height(12.dp))
            }
            // H1
            line.startsWith("# ") -> {
                Text(line.removePrefix("# "), color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
            }
            // H2
            line.startsWith("## ") -> {
                Text(line.removePrefix("## "), color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = BORDER, modifier = Modifier.padding(vertical = 6.dp))
            }
            // H3
            line.startsWith("### ") -> {
                Text(line.removePrefix("### "), color = ACCENT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
            }
            // Blockquote
            line.startsWith("> ") -> {
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(modifier = Modifier.width(3.dp).height(20.dp).background(ACCENT))
                    Spacer(modifier = Modifier.width(10.dp))
                    InlineText(line.removePrefix("> "), onWikiLink = onWikiLink)
                }
            }
            // Bullet list
            line.matches(VAULT_BULLET) -> {
                Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
                    Text("•  ", color = ACCENT, fontSize = 14.sp)
                    InlineText(line.substring(2), onWikiLink = onWikiLink)
                }
            }
            // Horizontal rule
            line.matches(VAULT_HR) -> {
                HorizontalDivider(color = BORDER, modifier = Modifier.padding(vertical = 8.dp))
            }
            // Blank line → paragraph gap
            line.isBlank() -> Spacer(modifier = Modifier.height(8.dp))
            // Normal paragraph line
            else -> InlineText(line, onWikiLink = onWikiLink)
        }
        i++
    }
}

@Composable
private fun InlineText(raw: String, onWikiLink: (String) -> Unit) {
    val annotated = buildAnnotatedString {
        var pos = 0
        val pattern = VAULT_INLINE
        pattern.findAll(raw).forEach { match ->
            if (pos < match.range.first) append(raw.substring(pos, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                        append(match.groupValues[1])
                    }
                match.groupValues[2].isNotEmpty() ->
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface)) {
                        append(match.groupValues[2])
                    }
                match.groupValues[3].isNotEmpty() ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = GREEN,
                        background = SURFACE)) {
                        append(match.groupValues[3])
                    }
                match.groupValues[4].isNotEmpty() -> {
                    val title = match.groupValues[4]
                    pushStringAnnotation("wikilink", title)
                    withStyle(SpanStyle(color = ACCENT, fontWeight = FontWeight.Medium)) {
                        append("[[${title}]]")
                    }
                    pop()
                }
            }
            pos = match.range.last + 1
        }
        if (pos < raw.length) append(raw.substring(pos))
    }

    val wikiLinks = annotated.getStringAnnotations("wikilink", 0, annotated.length)
    if (wikiLinks.isEmpty()) {
        Text(annotated, color = MUTED.copy(alpha = 1f).let { MaterialTheme.colorScheme.onSurface }, fontSize = 14.sp,
            lineHeight = 22.sp, modifier = Modifier.padding(bottom = 2.dp))
    } else {
        androidx.compose.foundation.text.ClickableText(
            text = annotated,
            style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 22.sp),
            onClick = { offset ->
                annotated.getStringAnnotations("wikilink", offset, offset)
                    .firstOrNull()?.let { onWikiLink(it.item) }
            },
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }
}

@Composable
private fun CodeBlock(code: String, lang: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SURFACE)
            .padding(12.dp)
    ) {
        if (lang != null) {
            Text(lang, color = MUTED, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(code, color = GREEN, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp)
    }
}

private enum class VaultMode { LIST, VIEW, EDIT }
