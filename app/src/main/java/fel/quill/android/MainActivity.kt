package fel.quill.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.runtime.mutableStateOf
import fel.quill.android.ui.QuillApp
import fel.quill.android.ui.QuillTheme

class MainActivity : ComponentActivity() {
    private val pairingUriState = mutableStateOf<Uri?>(null)
    private val sharedTextState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ReminderScheduler.schedule(this)
        ReminderWorker.createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
        val sharedText = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val pairingUri = if (intent?.data?.scheme == "quill") intent.data else null
        sharedTextState.value = sharedText
        pairingUriState.value = pairingUri
        setContent {
            QuillTheme {
                QuillApp(sharedText = sharedTextState.value, pairingUri = pairingUriState.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) sharedTextState.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (intent.data?.scheme == "quill") pairingUriState.value = intent.data
    }
}
