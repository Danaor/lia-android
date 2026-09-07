package app.lia.android

import android.content.Context
import app.lia.android.backend.Backend
import app.lia.android.backend.BackendId
import app.lia.android.backend.Router
import app.lia.android.store.History
import app.lia.android.store.LexiconStore
import app.lia.android.store.Secrets
import app.lia.android.store.Settings
import app.lia.android.text.Corrections
import app.lia.android.text.Lexicon
import app.lia.android.text.PostProcess
import app.lia.android.vocab.PromptComposer
import app.lia.android.vocab.VocabStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Manual dependency graph. One object, no framework: the app is small enough
 * that a DI library would be more moving parts than it saves.
 */
class AppGraph private constructor(context: Context) {

    private val app = context.applicationContext

    /** Survives the Activity so a long transcription is not killed by a rotation. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = Settings(app)
    val secrets = Secrets(app)
    val history = History(File(app.filesDir, "history.json"))
    val lexiconStore = LexiconStore(File(app.filesDir, "lexicon"))

    val vocabularyFile: File get() = File(app.filesDir, "vocabulary.json")

    @Volatile
    var vocabulary: VocabStore = VocabStore.load(vocabularyFile)
        private set

    @Volatile
    var lexicon: Lexicon = Lexicon(null)

    fun reloadVocabulary() {
        vocabulary = VocabStore.load(vocabularyFile)
    }

    fun replaceVocabulary(store: VocabStore) {
        store.save(vocabularyFile)
        vocabulary = store
    }

    suspend fun loadLexicon() {
        lexicon = lexiconStore.load()
    }

    /** The terms sent to a cloud model, composed exactly like the desktop's. */
    fun promptTerms(): List<String> {
        val fromStore = vocabulary.promptTerms()
        if (fromStore.isNotEmpty()) return fromStore
        return PromptComposer.fromPlainList(settings.state.value.manualVocabulary)
    }

    fun corrections(): List<Corrections.Pair> = vocabulary.corrections

    fun postProcessOptions(): PostProcess.Options {
        val snapshot = settings.state.value
        return PostProcess.Options(
            corrections = corrections(),
            lexicon = lexicon,
            protectedTerms = vocabulary.approvedTerms.map { it.term }.toSet(),
            applyLexiconFix = snapshot.lexiconFixEnabled,
            collectOov = snapshot.lexiconSuggestEnabled,
        )
    }

    fun backends(): Map<BackendId, Backend> {
        val snapshot = settings.state.value
        return Router.build(
            serverUrl = snapshot.serverUrl,
            serverToken = secrets.get(Secrets.Key.SERVER_TOKEN),
            allowInsecure = snapshot.allowInsecure,
            groqKey = secrets.get(Secrets.Key.GROQ),
            openAiKey = secrets.get(Secrets.Key.OPENAI),
            openAiModel = snapshot.openAiModel,
            geminiKey = secrets.get(Secrets.Key.GEMINI),
        )
    }

    fun router(): Router {
        val snapshot = settings.state.value
        return Router(
            backends = backends(),
            primary = snapshot.primary,
            fallback = snapshot.fallback,
            language = snapshot.language,
            hintsProvider = ::promptTerms,
            postProcessOptions = ::postProcessOptions,
        )
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
