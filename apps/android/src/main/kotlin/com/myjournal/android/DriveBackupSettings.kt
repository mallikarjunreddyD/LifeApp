package com.myjournal.android

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.Scope
import com.myjournal.domain.CloudBackupVersion
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun DriveBackupSettings(operations: DataOperations) {
    Text("Google Drive backup", style = MaterialTheme.typography.titleLarge)
    if (Build.VERSION.SDK_INT < 26) {
        Text("Encrypted Drive backups require Android 8 or later.")
        return
    }
    val context = LocalContext.current
    val automatic = (context.applicationContext as JournalApplication).automaticBackup
    val automaticState by automatic.state.collectAsState()
    val scope = rememberCoroutineScope()
    var enrolling by remember { mutableStateOf(false) }
    var automaticError by remember { mutableStateOf<String?>(null) }
    var localVersions by remember { mutableStateOf<List<LocalAutomaticBackup>?>(null) }
    var localRestore by remember { mutableStateOf<LocalAutomaticBackup?>(null) }
    var localCopy by remember { mutableStateOf<LocalAutomaticBackup?>(null) }
    var encryptedFile by remember { mutableStateOf<android.net.Uri?>(null) }
    val selectEncrypted = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { encryptedFile = it }
    val saveEncrypted = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val version = localCopy
        localCopy = null
        if (uri != null && version != null) operations.saveAutomaticBackupCopy(uri, version)
    }
    val prefs = remember(context) { context.getSharedPreferences("drive-backup", Context.MODE_PRIVATE) }
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val state by operations.state.collectAsState()
    var account by remember { mutableStateOf(prefs.getString("account", null)) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var authorizing by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showOlderDrive by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<CloudBackupVersion?>(null) }
    val scopes = remember { listOf(Scope(BACKUP_DRIVE_SCOPE)) }

    fun finish(name: String?, grant: AuthorizationResult?) {
        authorizing = false
        selected = null
        val access = grant?.accessToken
        if (name == null || grant == null || access.isNullOrBlank() || BACKUP_DRIVE_SCOPE !in grant.grantedScopes) {
            error = "Drive authorization was canceled or the requested access was declined."
            return
        }
        token = access
        account = name
        prefs.edit().putString("account", name).apply()
        error = null
        automatic.scheduleUpload()
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val grant = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching { client.getAuthorizationResultFromIntent(result.data!!) }.getOrNull()
        } else null
        finish(selected, grant)
    }

    fun authorize(name: String) {
        authorizing = true
        error = null
        selected = name
        operations.clearDriveVersions()
        val oldToken = token
        token = null
        fun request() {
            client.authorize(AuthorizationRequest.builder().setAccount(Account(name, "com.google"))
                .setRequestedScopes(scopes).build())
                .addOnSuccessListener { grant ->
                    val resolution = grant.pendingIntent
                    if (resolution == null) finish(name, grant)
                    else try {
                        consent.launch(IntentSenderRequest.Builder(resolution).build())
                    } catch (_: Exception) {
                        authorizing = false
                        selected = null
                        error = "Could not open Google’s authorization screen."
                    }
                }
                .addOnFailureListener {
                    authorizing = false
                    selected = null
                    error = "Google authorization failed. Check the Drive API, OAuth app registration and test account."
                }
        }
        if (oldToken == null) request()
        else client.clearToken(ClearTokenRequest.builder().setToken(oldToken).build()).addOnCompleteListener { request() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && !name.isNullOrBlank()) authorize(name)
        else authorizing = false
    }

    val enabled = !state.busy && !authorizing
    Text(account?.let { if (token != null) "Connected as $it" else "Selected account: $it · reauthorization required" }
        ?: "Not connected")
    Text("Drive backups are encrypted with a passphrase and kept as separate versions. Live synchronization is unavailable.")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row {
        TextButton(enabled = enabled && account == null, onClick = {
            try {
                authorizing = true
                val options = AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(listOf("com.google")).setAlwaysShowAccountPicker(true).build()
                picker.launch(AccountPicker.newChooseAccountIntent(options))
            } catch (_: Exception) {
                authorizing = false
                error = "No Google account chooser is available on this device."
            }
        }) { Text("Connect Google Account") }
    }
    account?.let { name ->
        Row {
            TextButton(enabled = enabled, onClick = { authorize(name) }) { Text("Reauthorize") }
            TextButton(enabled = enabled, onClick = {
                token = null
                account = null
                prefs.edit().remove("account").apply()
                operations.clearDriveVersions()
                error = null
                authorizing = true
                client.revokeAccess(RevokeAccessRequest.builder().setAccount(Account(name, "com.google"))
                    .setScopes(scopes).build()).addOnCompleteListener { result ->
                    authorizing = false
                    if (!result.isSuccessful) error = "Disconnected on this device. Revoke the Google grant in your account settings if needed."
                }
            }) { Text("Disconnect") }
        }
    }
    HorizontalDivider()
    Text("Automatic Android backup", style = MaterialTheme.typography.titleMedium)
    Text(if (automatic.enabled()) "Scheduled approximately every 24 hours" else "Automatic backup is off")
    automaticState.local?.let { Text("Last verified Android copy: ${readableBackupTime(it)}") }
    automaticState.drive?.let { Text("Last verified Android Drive upload: ${readableBackupTime(it)}") }
    automaticState.message?.let { Text(it) }
    automaticError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (automatic.enabled()) {
        OutlinedButton(enabled = enabled, onClick = automatic::captureNow) { Text("Back up Android now") }
        TextButton(enabled = enabled, onClick = { scope.launch { automatic.pause() } }) { Text("Pause automatic backup") }
    }
    TextButton(enabled = enabled && account != null, onClick = { enrolling = true }) {
        Text(if (automatic.enabled()) "Change recovery passphrase" else "Enable automatic backup")
    }
    if (enrolling) BackupPassphraseDialog(true, { enrolling = false }, automatic = true) { secret ->
        enrolling = false
        val name = account
        if (name == null) secret.fill('\u0000')
        else scope.launch {
            try {
                automatic.enroll(name, secret)
                automatic.schedule()
                automatic.captureNow()
                automaticError = null
            } catch (_: Exception) {
                automaticError = "Automatic backup could not be enrolled. Check device security and the Google account."
            }
        }
    }
    TextButton(enabled = enabled, onClick = {
        scope.launch {
            try { localVersions = automatic.localBackups() }
            catch (_: Exception) { automaticError = "Managed local backups could not be listed." }
        }
    }) { Text("View local recovery copies") }
    localVersions?.let { versions ->
        if (versions.isEmpty()) Text("No verified automatic local backups.")
        versions.forEach { version ->
            Text("${readableBackupTime(version.createdAt)} · ${(version.size + 1023) / 1024} KiB · Encrypted Android backup")
            TextButton(enabled = enabled, onClick = { localRestore = version }) { Text("Restore this copy") }
            TextButton(enabled = enabled, onClick = {
                localCopy = version
                saveEncrypted.launch(exportName("LifeApp-Encrypted-Android", "bin"))
            }) { Text("Save encrypted copy") }
        }
    }
    TextButton(enabled = enabled, onClick = { selectEncrypted.launch(arrayOf("application/octet-stream", "application/zip")) }) {
        Text("Open encrypted recovery file")
    }
    if (localRestore != null || encryptedFile != null) {
        BackupPassphraseDialog(false, { localRestore = null; encryptedFile = null }) { secret ->
            val version = localRestore
            val uri = encryptedFile
            localRestore = null
            encryptedFile = null
            when {
                version != null -> operations.inspectAutomaticBackup(version, secret)
                uri != null -> operations.inspectEncryptedFile(uri, secret)
                else -> secret.fill('\u0000')
            }
        }
    }
    token?.let { access ->
        Row {
            OutlinedButton(enabled = enabled, onClick = { creating = true }) { Text("Backup to Drive") }
            TextButton(enabled = enabled, onClick = { operations.listDriveBackups(DriveBackupStorage(access)) }) {
                Text("List Drive backups")
            }
        }
        state.driveVersions?.let { versions ->
            if (versions.isEmpty()) Text("No Drive backups found.")
            if (versions.size > 2) TextButton(onClick = { showOlderDrive = !showOlderDrive }) {
                Text(if (showOlderDrive) "Show latest Drive copies" else "Show older Drive copies")
            }
            for (version in if (showOlderDrive) versions else versions.take(2)) TextButton(enabled = enabled, onClick = { restoring = version }) {
                val local = version.createdAt.toLocalDateTime(TimeZone.currentSystemDefault())
                Text("Restore backup · ${local.date} ${local.time.toString().take(5)}")
            }
        }
        if (creating || restoring != null) {
            val restore = restoring
            BackupPassphraseDialog(creating, dismiss = { creating = false; restoring = null }) { secret ->
                creating = false
                restoring = null
                val drive = DriveBackupStorage(access)
                if (restore == null) operations.backupToDrive(drive, secret)
                else operations.inspectDriveBackup(drive, restore, secret)
            }
        }
    }
}

@Composable
private fun BackupPassphraseDialog(creating: Boolean, dismiss: () -> Unit, automatic: Boolean = false, submit: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (automatic) "Enroll automatic backup" else if (creating) "Encrypt Drive backup" else "Unlock encrypted backup") },
        text = {
            Column {
                Text(if (automatic) "Save this passphrase in a password manager or other independent location before enrolling. Keep previous passphrases for old backups. LifeApp retains the two latest verified managed copies after a successful replacement."
                    else if (creating) "Keep this passphrase somewhere safe. It is needed to restore this backup."
                    else "Enter the passphrase used to encrypt this backup.")
                OutlinedTextField(password, { if (it.length <= 1024) password = it }, label = { Text("Passphrase") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation())
                if (creating) OutlinedTextField(repeat, { if (it.length <= 1024) repeat = it },
                    label = { Text("Repeat passphrase") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton(enabled = password.length >= 12 && (!creating || password == repeat), onClick = {
                val secret = password.toCharArray()
                password = ""
                repeat = ""
                submit(secret)
            }) { Text(if (automatic) "Enable automatic backup" else if (creating) "Encrypt and back up" else "Validate backup") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
