package app.lia.android.ime

import android.app.Activity
import android.os.Bundle

/**
 * An invisible doorway between the Quick Settings tile and the dictation.
 *
 * Android 14 and up refuse to give the microphone to a foreground service that
 * was started from the background, and a tile tap counts as background: the
 * first attempt died with
 * "Foreground service started from background can not have microphone access".
 *
 * Coming through an activity puts the app in the foreground for the instant it
 * takes to start the service legally. It then finishes at once, so the field
 * behind it gets its focus straight back, while the service keeps the
 * microphone it was granted.
 */
class TileLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DictationService.isActive.value) {
            DictationService.stop(this)
        } else {
            DictationService.start(this)
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
