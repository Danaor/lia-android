package app.lia.android

import android.app.Application
import app.lia.android.ime.BubbleService
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        // The dictionary is optional and big; load it in the background so the
        // spelling guard simply does nothing until it is ready.
        graph.scope.launch { graph.loadLexicon() }
    }

    /**
     * A reboot takes the floating button with it, and Android 14 will not let a
     * microphone service start from BOOT_COMPLETED. Bringing it back when the
     * app is next opened is the honest compromise.
     *
     * Called from the Activity, never from here: this runs whenever the process
     * wakes for ANY reason, including for a service, and a microphone service
     * started from the background is refused the microphone outright.
     */
    fun restoreBubble() {
        if (!graph.settings.state.value.bubbleEnabled) return
        if (!BubbleService.canDrawOverlays(this)) return
        runCatching { BubbleService.start(this) }
    }
}
