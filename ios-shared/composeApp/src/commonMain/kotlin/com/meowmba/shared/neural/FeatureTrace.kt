package com.meowmba.shared.neural

import kotlinx.serialization.Serializable

/**
 * One controller tick, as recorded for offline analysis and training.
 *
 * Only *features* are stored: the encoded input vector, both controllers'
 * outputs and the legacy drives. Never audio, never video, never transcripts.
 * That is enough to fit a readout offline and to replay a session through the
 * cores on a laptop, and it is nothing a person would mind existing.
 *
 * ## Port note
 *
 * Only the two wire-format types are ported. The original's
 * `FeatureTraceRecorder` — the append-only JSONL writer with a per-file byte cap
 * and a file-count retention cap — is **not** here, because it is built on
 * `java.io.File` and `java.io.Writer`. Porting it needs a file seam, and the
 * port's existing one (`ModelFile`) lives in the `agent` package, which `neural`
 * must not depend on. The controller only ever needs [FeatureTraceSample] as the
 * type of its optional `traceSink`, so nothing in this port is blocked by the
 * omission.
 *
 * `@Serializable` is kept on both, so whichever platform eventually writes
 * traces writes the identical format the Android build does.
 */
@Serializable
data class FeatureTraceSample(
    val tMs: Long,
    val mode: String,
    val input: FloatArray,
    val legacyBias: FloatArray,
    val neuralReadout: FloatArray,
    val neuralBias: FloatArray? = null,
    val legacyDrives: FloatArray,
)

/**
 * Header written once per file so the trainer can check layouts before reading
 * samples.
 */
@Serializable
data class FeatureTraceHeader(
    val version: Int = 1,
    val inputLayout: List<String>,
    val outputLayout: List<String>,
    val biasFields: List<String>,
    val driveFields: List<String> = listOf("approach", "social", "curiosity", "caution", "energy"),
    val checkpoint: String,
    val startedAtEpochMs: Long,
) {
    companion object {
        /**
         * The header for [checkpoint].
         *
         * The original has this as `FeatureTraceRecorder.headerFor`, but nothing
         * in it touches the filesystem — the layouts come from the checkpoint and
         * the field names from [DriveBias]. It moves onto the header itself so a
         * valid header can be built without the unported recorder.
         */
        fun forCheckpoint(checkpoint: NeuralCheckpoint): FeatureTraceHeader = FeatureTraceHeader(
            inputLayout = checkpoint.inputLayout,
            outputLayout = checkpoint.outputLayout,
            biasFields = DriveBias.FIELD_NAMES,
            checkpoint = checkpoint.name,
            startedAtEpochMs = 0L,
        )
    }
}
