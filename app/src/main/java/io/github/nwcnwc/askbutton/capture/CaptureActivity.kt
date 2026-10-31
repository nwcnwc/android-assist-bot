package io.github.nwcnwc.askbutton.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import io.github.nwcnwc.askbutton.ask.AskActivity

/**
 * Quick Settings and the launcher shortcut land here. The system consent dialog
 * is required for every capture on Android 14+.
 */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST || resultCode != RESULT_OK || data == null) {
            finish()
            return
        }
        CaptureBridge.arm(resultCode, data)
        startForegroundService(Intent(this, ProjectionService::class.java))
        Thread {
            val ready = CaptureBridge.awaitForeground()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (!ready) {
                    Toast.makeText(this, "Couldn't start screen capture.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                moveTaskToBack(true)
                CaptureBridge.allowGrab()
            }
            val gotShot = CaptureBridge.awaitShot()
            runOnUiThread {
                if (gotShot && CaptureBridge.claim()) {
                    startActivity(Intent(this, AskActivity::class.java))
                }
                finish()
            }
        }.start()
    }

    private companion object {
        const val REQUEST = 7
    }
}
