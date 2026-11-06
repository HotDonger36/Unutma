package com.example.unutma.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.unutma.data.Backup
import com.example.unutma.data.KeepImport
import com.example.unutma.data.Repo
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings › Backup: back up everything to a .zip (save it in Downloads, Google Drive… or share it),
 * restore from one, and import from a Google Keep export.
 */
@Composable
fun BackupSection(unlock: (onSuccess: () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var backupChoice by remember { mutableStateOf(false) }

    /** Locked notes can only go into a backup unlocked, so ask for the fingerprint first when there are any. */
    fun withUnlock(run: () -> Unit) {
        Repo.scope.launch {
            val anyLocked = Repo.notes(ctx).all().any { it.isLocked }
            withContext(Dispatchers.Main) { if (anyLocked) unlock(run) else run() }
        }
    }

    fun report(title: String, r: Backup.Result) {
        message = title to buildString {
            append("${r.notes} note${if (r.notes == 1) "" else "s"} backed up.")
            if (r.lockedLeftOut > 0) append(" ${r.lockedLeftOut} locked note${if (r.lockedLeftOut == 1) "" else "s"} couldn't be unlocked and were left out — try again.")
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) withUnlock {
            busy = "Backing up…"
            Repo.scope.launch {
                val r = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { Backup.write(ctx, it) } }
                withContext(Dispatchers.Main) {
                    busy = null
                    r.getOrNull()?.let { report("Backup saved", it) } ?: run { message = "Backup failed" to (r.exceptionOrNull()?.message ?: "Couldn't write the file.") }
                }
            }
        }
    }

    fun share() = withUnlock {
        busy = "Backing up…"
        Repo.scope.launch {
            val r = runCatching {
                val f = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, Backup.fileName())
                val res = f.outputStream().use { Backup.write(ctx, it) }
                f to res
            }
            withContext(Dispatchers.Main) {
                busy = null
                val (f, res) = r.getOrNull() ?: run { message = "Backup failed" to (r.exceptionOrNull()?.message ?: ""); return@withContext }
                if (res.lockedLeftOut > 0) report("Backup ready", res)
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, "Share backup"))
            }
        }
    }

    val zipTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Restoring…"
        Repo.scope.launch {
            val r = runCatching { Backup.restore(ctx, uri) }
            withContext(Dispatchers.Main) {
                busy = null
                message = r.fold(
                    onSuccess = { "Restored" to "${it.notes} note${if (it.notes == 1) "" else "s"} added" + (if (it.labels > 0) ", ${it.labels} label${if (it.labels == 1) "" else "s"}" else "") + "." + (if (it.skipped > 0) " ${it.skipped} were already here." else "") + " Reminders and pins are set up again." },
                    onFailure = { "Couldn't restore" to (it.message ?: "That file couldn't be read.") },
                )
            }
        }
    }
    val keep = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Importing from Google Keep…"
        Repo.scope.launch {
            val r = runCatching { KeepImport.run(ctx, uri) }
            withContext(Dispatchers.Main) {
                busy = null
                message = r.fold(
                    onSuccess = { "Imported from Google Keep" to KeepImport.summary(it) },
                    onFailure = { "Couldn't import" to (it.message ?: "That file couldn't be read.") },
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("BACKUP", style = Ty.settingsHeader, color = C.muted, modifier = Modifier.padding(bottom = 4.dp))
        BackupRow("Back up", UI.Share) { backupChoice = true }
        BackupRow("Restore from a backup", UI.Restore) { restore.launch(zipTypes) }
        BackupRow("Import from Google Keep", UI.Copy) { keep.launch(zipTypes) }
    }

    if (backupChoice) UDialog({ backupChoice = false }, "Back up") {
        Text(
            "Everything goes into one file: notes, checklists, labels, the archive and recently deleted, pictures, voice notes and settings.",
            style = Ty.rowSub, color = C.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        DialogRow("Save it (Downloads, Google Drive…)", { backupChoice = false; save.launch(Backup.fileName()) }, UI.Archive)
        DialogRow("Share it", { backupChoice = false; share() }, UI.Share)
    }
    busy?.let { text ->
        UDialog({}, null) { Text(text, style = Ty.row, color = C.fg, modifier = Modifier.padding(20.dp)) }
    }
    message?.let { (title, text) ->
        UDialog({ message = null }, title) {
            Text(text, style = Ty.row, color = C.body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { Spacer(Modifier.weight(1f)); TextButtonU("OK", { message = null }) }
        }
    }
}

@Composable
private fun BackupRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ty.row, color = C.fg, modifier = Modifier.weight(1f))
            Icon(icon, null, tint = C.faint, modifier = Modifier.size(14.dp))
        }
        HDivider()
    }
}
