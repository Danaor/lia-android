package app.lia.android

import android.app.Application
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
}
