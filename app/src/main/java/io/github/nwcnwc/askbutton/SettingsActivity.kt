package io.github.nwcnwc.askbutton

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.github.nwcnwc.askbutton.diag.Diagnostics
import io.github.nwcnwc.askbutton.logic.HostChoice
import io.github.nwcnwc.askbutton.logic.HostKind
import io.github.nwcnwc.askbutton.logic.SetupLink
import io.github.nwcnwc.askbutton.logic.WebUrls
import io.github.nwcnwc.askbutton.net.SendService
import io.github.nwcnwc.askbutton.notify.Notices
import io.github.nwcnwc.askbutton.store.LastRequest
import io.github.nwcnwc.askbutton.store.Settings
import java.net.URI

class SettingsActivity : Activity() {
    private lateinit var webhook: EditText
    private lateinit var auth: EditText
    private lateinit var imgbb: EditText
    private lateinit var upload: EditText
    private lateinit var assistantButton: Button
    private lateinit var micButton: Button
    private lateinit var noteButton: Button
    private lateinit var checklist: TextView
    private lateinit var lastRequest: TextView
    private lateinit var logView: TextView
    private var askedClipboard = false
    private var advancedOpen = false
    private val requestDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Notices.ensure(this)
        webhook = findViewById(R.id.webhook)
        auth = findViewById(R.id.auth)
        imgbb = findViewById(R.id.imgbb)
        upload = findViewById(R.id.upload)
        assistantButton = findViewById(R.id.assistant_button)
        checklist = findViewById(R.id.checklist)
        lastRequest = findViewById(R.id.last_request)
        logView = findViewById(R.id.log)
        micButton = findViewById(R.id.mic_button)
        noteButton = findViewById(R.id.note_button)
        wireReveal(auth, findViewById(R.id.auth_toggle))
        wireReveal(imgbb, findViewById(R.id.imgbb_toggle))
        findViewById<Button>(R.id.save).setOnClickListener {
            if (saveFromFields()) toast(getString(R.string.saved))
        }
        findViewById<Button>(R.id.test).setOnClickListener { testSend() }
        findViewById<Button>(R.id.advanced_toggle).setOnClickListener { toggleAdvanced() }
        micButton.setOnClickListener { requestIfNeeded(Manifest.permission.RECORD_AUDIO) }
        noteButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestIfNeeded(Manifest.permission.POST_NOTIFICATIONS)
        }
        loadFields()
        val appliedLink = handleIntent(intent)
        maybeRequestPermissions()
        if (!appliedLink) maybeClipboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshAssistant()
        refreshPermissions()
        refreshStatus()
        val filter = IntentFilter(LastRequest.ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(requestDone, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(requestDone, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(requestDone)
        } catch (_: Exception) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ROLE) refreshAssistant()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    private fun handleIntent(incoming: Intent?): Boolean {
        if (incoming?.action != Intent.ACTION_VIEW) return false
        val data = incoming.dataString ?: return false
        val params = SetupLink.parse(data) ?: return false
        return apply(params, data)
    }

    private fun maybeClipboard() {
        if (askedClipboard) return
        askedClipboard = true
        val text = readClipboard() ?: return
        val params = SetupLink.parse(text) ?: return
        val stamp = text.hashCode().toString()
        val settings = Settings(this)
        if (settings.isClipDismissed(stamp)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_title)
            .setMessage(params.webhook ?: getString(R.string.clipboard_body))
            .setPositiveButton(R.string.save) { _, _ ->
                settings.dismissedClip(stamp)
                apply(params, text)
            }
            .setNegativeButton(R.string.not_now) { _, _ ->
                settings.dismissedClip(stamp)
            }
            .show()
    }

    private fun apply(params: SetupLink.Params, stampSource: String): Boolean {
        if (params.webhook != null && !WebUrls.isHttpsUrl(params.webhook)) {
            toast(getString(R.string.webhook_https))
            return false
        }
        if (params.uploadUrl != null && !WebUrls.isHttpsUrl(params.uploadUrl)) {
            toast(getString(R.string.upload_https))
            return false
        }
        val settings = Settings(this)
        try {
            params.webhook?.let { settings.webhook = it }
            params.authorization?.let { settings.authorization = it }
            params.imgbbKey?.let { settings.imgbbKey = it }
            params.uploadUrl?.let { settings.uploadUrl = it }
            settings.dismissedClip(stampSource.hashCode().toString())
        } catch (_: Exception) {
            toast(getString(R.string.store_failed))
            return false
        }
        loadFields()
        refreshStatus()
        val host = params.webhook?.let { runCatching { URI(it).host }.getOrNull() }
        toast(if (host.isNullOrBlank()) getString(R.string.saved) else getString(R.string.saved_host, host))
        return true
    }

    private fun saveFromFields(): Boolean {
        val webhookText = webhook.text.toString().trim()
        val uploadText = upload.text.toString().trim()
        if (webhookText.isNotEmpty() && !WebUrls.isHttpsUrl(webhookText)) {
            toast(getString(R.string.webhook_https))
            return false
        }
        if (uploadText.isNotEmpty() && !WebUrls.isHttpsUrl(uploadText)) {
            toast(getString(R.string.upload_https))
            return false
        }
        return try {
            val settings = Settings(this)
            settings.webhook = webhookText
            settings.authorization = auth.text.toString()
            settings.imgbbKey = imgbb.text.toString()
            settings.uploadUrl = uploadText
            refreshStatus()
            true
        } catch (_: Exception) {
            toast(getString(R.string.store_failed))
            false
        }
    }

    private fun loadFields() {
        val settings = Settings(this)
        webhook.setText(settings.webhook)
        auth.setText(settings.authorization)
        imgbb.setText(settings.imgbbKey)
        upload.setText(settings.uploadUrl)
    }

    private fun testSend() {
        if (!saveFromFields()) return
        val app = applicationContext
        try {
            SendService.enqueue(
                app,
                SendService.intent(app, "Test from Ask Button", "", null, arrayListOf(), null, null),
            )
            toast(getString(R.string.sending))
        } catch (error: Exception) {
            toast(error.message ?: getString(R.string.send_failed))
        }
    }

    private fun refreshStatus() {
        val settings = Settings(this)
        val roles = getSystemService(RoleManager::class.java)
        val assistant = roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val image = when (HostChoice.kind(settings.imgbbKey, settings.uploadUrl)) {
            HostKind.IMGBB -> "ImgBB key: set"
            HostKind.CUSTOM -> "Image host: custom URL"
            HostKind.NONE -> "ImgBB key: missing"
        }
        checklist.text = listOf(
            mark(settings.webhook.isNotBlank(), "Webhook URL"),
            mark(settings.authorization.isNotBlank(), "Authorization header"),
            image,
            if (assistant) "Digital assistant: on" else "Digital assistant: off",
            if (mic) "Microphone: allowed" else "Microphone: not allowed",
        ).joinToString("\n")
        lastRequest.text = LastRequest.line(this)
        if (advancedOpen) logView.text = Diagnostics.read(this)
    }

    private fun mark(ok: Boolean, label: String): String = if (ok) "$label: set" else "$label: missing"

    private fun toggleAdvanced() {
        advancedOpen = !advancedOpen
        findViewById<View>(R.id.advanced).visibility = if (advancedOpen) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.advanced_toggle).setText(
            if (advancedOpen) R.string.advanced_hide else R.string.advanced,
        )
        if (advancedOpen) logView.text = Diagnostics.read(this)
    }

    private fun refreshAssistant() {
        val roles = getSystemService(RoleManager::class.java)
        val available = roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        val held = available && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        assistantButton.setText(if (held) R.string.assistant_manage else R.string.assistant_set)
        assistantButton.setOnClickListener {
            if (!available) {
                toast(getString(R.string.assistant_missing))
                return@setOnClickListener
            }
            if (held) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), REQUEST_ROLE)
            }
        }
    }

    private fun refreshPermissions() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micButton.setText(if (mic) R.string.mic_allowed else R.string.mic_allow)
        micButton.isEnabled = !mic
        if (Build.VERSION.SDK_INT >= 33) {
            noteButton.visibility = View.VISIBLE
            val allowed = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            noteButton.setText(if (allowed) R.string.notes_allowed else R.string.notes_allow)
            noteButton.isEnabled = !allowed
        } else {
            noteButton.visibility = View.GONE
        }
    }

    private fun maybeRequestPermissions() {
        val needed = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMS)
        }
    }

    private fun requestIfNeeded(permission: String) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(permission), REQUEST_PERMS)
    }

    private fun readClipboard(): String? {
        return try {
            val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            if (!manager.hasPrimaryClip()) return null
            manager.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun wireReveal(field: EditText, button: Button) {
        button.setOnClickListener {
            val hidden = field.transformationMethod is PasswordTransformationMethod
            field.transformationMethod = if (hidden) null else PasswordTransformationMethod.getInstance()
            button.setText(if (hidden) R.string.hide else R.string.show)
            field.setSelection(field.text?.length ?: 0)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_ROLE = 3
        const val REQUEST_PERMS = 4
    }
}
