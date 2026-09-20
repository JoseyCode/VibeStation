package com.boogie.vibestation.share

import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.boogie.vibestation.models.Album
import com.boogie.vibestation.util.MusicLibraryUtil
import com.boogie.vibestation.util.PlaylistUtil
import com.boogie.vibestation.views.ShareCircleView
import java.util.concurrent.Executors

/**
 * The Share Mode screen: a full-screen [ShareCircleView] over a dark background. It asks for the Nearby
 * permissions, starts and binds [ShareService], shows whatever state the service reports, and opens a
 * picker when the user says yes while connected. Leaving the screen (back, not rotation) turns Share Mode
 * off, since nothing is shared while the screen is closed. In a debuggable build, launching with
 * [EXTRA_DEMO] shows sample states instead of touching the radio.
 */
@Suppress("TooManyFunctions") // lifecycle, permission and picker steps of one screen
class ShareActivity : AppCompatActivity() {
    private lateinit var circle: ShareCircleView
    private val worker = Executors.newSingleThreadExecutor()
    private var service: ShareService? = null
    private var demoIndex = 0

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), ::onPermissions)

    private val listener = object : ShareSession.Listener {
        override fun onState(state: ShareState) {
            circle.shareState = state
            if (state is ShareState.Closed) {
                state.reason?.let { Toast.makeText(this@ShareActivity, it, Toast.LENGTH_LONG).show() }
                finish()
            }
        }

        override fun onPickRequested() = showKindPicker()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ShareService.LocalBinder).service.also { it.attach(listener) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        circle = ShareCircleView(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(BACKGROUND)
        root.addView(circle, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        if (intent.getBooleanExtra(EXTRA_DEMO, false) && isDebuggable()) {
            startDemo(root)
        } else {
            circle.onYes = { service?.yes() }
            circle.onNo = { service?.no() }
            if (isDebuggable()) showDebugLog(root)
            requestAccess()
        }
    }

    override fun onDestroy() {
        service?.attach(null)
        if (service != null) unbindService(connection)
        if (isFinishing) service?.end()
        ShareDebugLog.onChange = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun requestAccess() {
        val bluetooth = getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetooth == null || !bluetooth.isEnabled) {
            stopWith("Turn on Bluetooth to use Share Mode.")
            return
        }
        val problem = radioProblem()
        if (problem != null) {
            stopWith(problem)
            return
        }
        val missing = SharePermissions.runtimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startSharing() else permissionRequest.launch(missing.toTypedArray())
    }

    /** Nearby silently finds nothing when Location is off (before Android 13), so say so before starting. */
    private fun radioProblem(): String? {
        val location = getSystemService(LocationManager::class.java)
        val off = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            (location == null || !LocationManagerCompat.isLocationEnabled(location))
        return if (off) "Turn on Location to use Share Mode." else null
    }

    /** Debug builds show the radio's own log over the circle, so two phones can be tested without a cable. */
    private fun showDebugLog(root: FrameLayout) {
        val text = TextView(this)
        text.setTextColor(Color.LTGRAY)
        text.textSize = DEBUG_TEXT_SP
        text.setBackgroundColor(Color.argb(DEBUG_ALPHA, 0, 0, 0))
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap, Gravity.BOTTOM))
        ShareDebugLog.onChange = { text.text = it }
    }

    private fun onPermissions(granted: Map<String, Boolean>) {
        if (granted.values.all { it }) startSharing() else stopWith("Share Mode needs the Nearby devices permission.")
    }

    private fun startSharing() {
        val intent = Intent(this, ShareService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    /** Debug review mode: tapping the button steps through every state; dragging just says what it would do. */
    private fun startDemo(root: FrameLayout) {
        val next = Button(this)
        next.text = "Next state"
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(next, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END))
        circle.onYes = { Toast.makeText(this, "Yes", Toast.LENGTH_SHORT).show() }
        circle.onNo = { Toast.makeText(this, "No", Toast.LENGTH_SHORT).show() }
        next.setOnClickListener {
            circle.shareState = ShareDemo.states[demoIndex % ShareDemo.states.size]
            demoIndex++
        }
        next.performClick()
    }

    private fun showKindPicker() {
        val kinds = listOf(ShareKind.SONG to "Song", ShareKind.ALBUM to "Album", ShareKind.PLAYLIST to "Playlist")
        AlertDialog.Builder(this)
            .setTitle("Send")
            .setItems(kinds.map { it.second }.toTypedArray()) { _, which -> loadLibraryThen(kinds[which].first) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Reads the library off the main thread, then shows the list for the chosen kind. */
    private fun loadLibraryThen(kind: ShareKind) {
        val prefs = getSharedPreferences("RetroPrefs", Context.MODE_PRIVATE)
        worker.execute {
            val albumMap = HashMap<String, Album>()
            val songs = MusicLibraryUtil.queryMediaStoreSongs(contentResolver, null, albumMap)
            val library = MusicLibraryUtil.assembleLibrary(songs, albumMap) { byId, byNameKey ->
                PlaylistUtil.parsePlaylists(prefs, byId, byNameKey)
            }
            val choices = ShareChoice.of(kind, library, contentResolver)
            runOnUiThread { showChoices(choices) }
        }
    }

    private fun showChoices(choices: List<ShareChoice>) {
        if (choices.isEmpty()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setItems(labels) { _, which -> prepareAndSend(choices[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Reading the files can take a moment, so it happens off the main thread. */
    private fun prepareAndSend(choice: ShareChoice) {
        worker.execute {
            sendPrepared(choice.prepare())
        }
    }

    private fun sendPrepared(offer: ShareOffer?) {
        runOnUiThread {
            if (offer == null) {
                Toast.makeText(this, "None of those songs can be sent", Toast.LENGTH_SHORT).show()
            } else {
                service?.offer(offer)
            }
        }
    }

    /** Intent extra names. */
    companion object {
        /** Intent extra that shows sample states instead of using the radio; honoured in debuggable builds only. */
        const val EXTRA_DEMO = "demo"

        private const val DEBUG_TEXT_SP = 10f
        private const val DEBUG_ALPHA = 160
        private val BACKGROUND = Color.rgb(16, 16, 20)
    }
}
