package io.github.nwcnwc.askbutton

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import io.github.nwcnwc.askbutton.logic.SetupLink
import io.github.nwcnwc.askbutton.logic.WebUrls
import io.github.nwcnwc.askbutton.net.Sender
import io.github.nwcnwc.askbutton.notify.Notices
import io.github.nwcnwc.askbutton.store.Settings
import java.net.URI

class SettingsActivity : Activity() {
    private lateinit var webhook: EditText
    private lateinit var auth: EditText
    private lateinit var imgbb: EditText
    private lateinit var upload: EditText
    private lateinit var assistantStatus: android.widget.TextView
    private lateinit var assistantButton: Button
    private lateinit var micButton: Button
    private lateinit var noteButton: Button
    private var askedClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Notices.ensure(this)
        webhook = findViewById(R.id.webhook)
        auth = findViewById(R.id.auth)
        imgbb = findViewById(R.id.imgbb)
        upload = findViewById(R.id.upload)
        assistantStatus = findViewById(R.id.assistant_status)
        assistantButton = findViewById(R.id.assistant_button)
        micButton = findViewById(R.id.mic_button)
        noteButton = findViewById(R.id.note_button)
        wireReveal(auth, findViewById(R.id.auth_toggle))
        wireReveal(imgbb, findViewById(R.id.imgbb_toggle))
        findViewById<Button>(R.id.save).setOnClickListener {
            if (saveFromFields()) toast(getString(R.string.saved))
        }
        findViewById<Button>(R.id.test).setOnClickListener { testSend() }
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
        val button = findViewById<Button>(R.id.test)
        button.isEnabled = false
        Sender.send(this, "Test from Ask Button", null, "", null, emptyList()) { outcome ->
            button.isEnabled = true
            when (outcome) {
                is Sender.Outcome.Sent -> Notices.sent(this, getString(R.string.sent))
                is Sender.Outcome.Failed -> Notices.problem(this, outcome.message)
            }
        }
    }

    private fun refreshAssistant() {
        val roles = getSystemService(RoleManager::class.java)
        val available = roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
        val held = available && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        assistantStatus.setText(if (held) R.string.assistant_on else R.string.assistant_off)
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
