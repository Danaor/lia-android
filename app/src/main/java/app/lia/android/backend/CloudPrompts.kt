package app.lia.android.backend

/**
 * The prompt text the cloud backends send (plan 3.2, 3.3, 5.1).
 *
 * Both strings are copied verbatim from Lia Desktop. Do not reword them: the
 * bias sentence is what keeps a Hebrew/English speaker from being transcribed
 * into a third language, and the verbatim instruction is what stops the
 * GPT-family models summarising or inventing words over silence.
 */
object CloudPrompts {

    const val BIAS_SENTENCE =
        "Bilingual transcription in Hebrew or English only. " +
            "שלום, תודה רבה, איך הולך, מחשב, פגישה. " +
            "Hello, thank you, how are you, meeting, computer, project."

    const val VERBATIM_INSTRUCTION =
        "Transcribe the audio verbatim in the language actually spoken. Do NOT " +
            "translate. Do NOT summarise. If Hebrew is spoken, output Hebrew. If " +
            "English is spoken, output English. If both languages are mixed in one " +
            "utterance, preserve each phrase in its original language. Output every " +
            "word the speaker said, including filler words and repetitions. " +
            "CRITICAL: If the audio contains no actual speech - only silence, " +
            "background noise, breathing, mouse clicks, keyboard sounds, fans, or " +
            "static - output an empty string. Do NOT invent words to fill the void. " +
            "An empty transcription is the correct output for an audio clip that " +
            "does not contain speech."

    fun isGptFamily(model: String): Boolean = model.startsWith("gpt-")

    /**
     * The verbatim instruction is not vocabulary, so it survives the bias gate
     * and is always present for a GPT-family model. The term list and the
     * bilingual bias sentence are both Latin-heavy and both gated by
     * [PromptHints.allowBias] (plan 4.5).
     */
    fun build(model: String, hints: PromptHints, language: Language): String {
        val parts = ArrayList<String>(3)
        if (isGptFamily(model)) parts.add(VERBATIM_INSTRUCTION)
        if (hints.allowBias) {
            if (hints.vocabulary.isNotEmpty()) {
                parts.add("Common terms: " + hints.vocabulary.joinToString(", ") + ".")
            }
            if (language == Language.AUTO) parts.add(BIAS_SENTENCE)
        }
        return parts.joinToString(" ").trim()
    }
}
