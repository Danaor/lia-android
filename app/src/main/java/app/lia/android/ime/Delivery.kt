package app.lia.android.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import app.lia.android.AppGraph
import app.lia.android.backend.Router
import app.lia.android.store.History

/**
 * What happens to a transcript produced OUTSIDE the app - by the floating
 * button, the Quick Settings tile, or anything else that dictates on top of
 * another app.
 *
 * Kept in one place so every surface behaves identically: same history entry,
 * same logging rules, same insert-or-clipboard fallback, same wording.
 */
object Delivery {

    sealed interface Outcome {
        /** Text went straight into the field the user was typing in. */
        data class Inserted(val text: String, val backend: String) : Outcome

        /** No inserter, or nothing editable in front: the clipboard has it. */
        data class Copied(val text: String, val backend: String, val reason: String) : Outcome

        data class Nothing(val message: String) : Outcome

        data class Failed(val message: String) : Outcome
    }

    fun deliver(
        context: Context,
        graph: AppGraph,
        outcome: Router.Outcome,
        source: String,
    ): Outcome = when (outcome) {
        is Router.Outcome.Success -> {
            val text = outcome.result.text
            val backend = outcome.result.backend.short
            graph.diagnostics.logTranscript(backend, outcome.result.elapsedMs, text)
            if (text.isBlank()) {
                Outcome.Nothing("Nothing was heard.")
            } else {
                graph.history.add(
                    History.Entry(
                        text = text,
                        backend = backend,
                        timestamp = System.currentTimeMillis(),
                        elapsedMs = outcome.result.elapsedMs,
                        source = source,
                    )
                )
                val wantsInsert = graph.settings.state.value.bubbleAutoInsert
                if (wantsInsert && TextInserter.insert(text)) {
                    Outcome.Inserted(text, backend)
                } else {
                    copy(context, text)
                    Outcome.Copied(
                        text,
                        backend,
                        if (wantsInsert && !TextInserter.isConnected) {
                            "Copied. Turn on Lia in Accessibility to insert it directly."
                        } else {
                            "Copied - tap paste on your keyboard."
                        },
                    )
                }
            }
        }
        is Router.Outcome.Rejected -> {
            graph.diagnostics.log("$source rejected: ${outcome.message}")
            Outcome.Nothing(outcome.message)
        }
        is Router.Outcome.Failed -> {
            graph.diagnostics.log("$source failed (${outcome.kind}): ${outcome.message}")
            Outcome.Failed(outcome.message)
        }
    }

    fun message(outcome: Outcome): String = when (outcome) {
        is Outcome.Inserted -> "Inserted by ${outcome.backend}"
        is Outcome.Copied -> outcome.reason
        is Outcome.Nothing -> outcome.message
        is Outcome.Failed -> outcome.message
    }

    private fun copy(context: Context, text: String) {
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("Lia", text))
        }
    }
}
