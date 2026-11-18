package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import android.content.Context
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetStats
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetRecognizer.Companion.DECODE
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetRecognizer.Companion.DECODE_1
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetRecognizer.Companion.ENCODE
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import timber.log.Timber

/**
 * Parakeet TDT on LiteRT: one 5 s window in, token ids out.
 *
 * This replaces MediaPipe's `AudioTranscriber`, which ran the `.litertlm` bundle on the CPU with no
 * way to reach the NPU. The `.tflite` graphs are the same weights exported for the LiteRT runtime
 * instead, so the pipeline around them has to be built here: features come from [ParakeetMel], the
 * encoder is one run, and the token-and-duration decoder is the greedy loop below.
 *
 * The decoder is where the interesting part is. The stateful export exposes three signatures:
 * `encode`, a stateless multi-token `decode` and a single-token stateful `decode_1` that carries the
 * prediction network's two LSTM state tensors. The first few tokens come from `decode`, which keeps
 * the sequence start in context, and the rest from `decode_1`, which advances the LSTM one token at
 * a time instead of recomputing the whole prefix. State buffers are swapped rather than copied:
 * the state written by one run is the buffer the next run reads.
 */
internal class ParakeetRecognizer(
    context: Context,
    private val encodeFile: File,
    /**
     * The graph the decoder runs. On the NPU it is not [encodeFile]: the G6 graph's compiled ops
     * cannot execute anywhere but the NPU, and the decoder's 60-90 single-token invocations per
     * window pay the NPU's dispatch cost on each one, so the portable graph on the CPU is faster for
     * that half. Anywhere else the two are the same file and no copy happens.
     */
    private val decodeFile: File,
    val backend: ParakeetBackend,
) : AutoCloseable {

    /** One recognised token and the encoder frame it starts at. */
    internal data class Token(val id: Int, val frame: Int)

    internal data class Window(
        val tokens: List<Token>,
        val frames: Int,
        val encodeMs: Long,
        val decodeMs: Long,
    )

    /** How long the runtime took to open the graphs; the first pass on a phone pays for it. */
    val loadMs: Long

    private val encodeEnvironment: Environment
    private val decodeEnvironment: Environment?
    private val encodeModel: CompiledModel
    private val decodeModel: CompiledModel

    /** True when one compiled model does both halves, which is every backend but the NPU. */
    private val sharedModel: Boolean

    private val encodeInputs: List<TensorBuffer>
    private val encodeOutputs: List<TensorBuffer>
    private val encodeFeatureCount: Int

    /**
     * The signature the encode buffers were created for, or null when the graph has no named
     * `encode` and its default entry point had to be used. The export this app ships has the name;
     * the fallback only exists so a re-export that drops it is a slow pass rather than a failed one.
     */
    private val encodeSignature: String?
    private val decodeInputs: List<TensorBuffer>
    private val decodeOutputs: List<TensorBuffer>
    private val decode1Inputs: List<TensorBuffer>?
    private val decode1Outputs: List<TensorBuffer>?

    /** Number of encoder frames the decoder walks, and tokens it accepts per run when stateless. */
    private val maxTimeIndex: Int
    private val tokenSlots: Int
    private val logitsPerToken: Int

    /**
     * The LSTM state, as two pairs of buffers: a run reads one pair and writes the other, then the
     * pairs swap. Pair 0 is allocated by `decode` and pair 1 by its outputs, and the same two are
     * reused after the switch to `decode_1` because the state tensors have the same shape in both
     * signatures.
     */
    private val statePairs: List<List<TensorBuffer>>
    private var stateIndex = 0

    /** Float count of one LSTM state tensor, which is all zeros when a window starts. */
    private val stateElements: Int

    private val blankTokenId = BLANK_TOKEN_ID

    /** The first window's tokens, logged once, because a wrong tensor layout looks like gibberish. */
    private var reportedShapes = false

    init {
        val startedAt = SystemClock.elapsedRealtime()
        sharedModel = encodeFile.absolutePath == decodeFile.absolutePath
        encodeEnvironment = if (backend == ParakeetBackend.NPU) {
            Environment.create(BuiltinNpuAcceleratorProvider(context))
        } else {
            Environment.create()
        }
        // CPU is always offered as a fallback so a partially delegated NPU graph still runs moved
        // ops on the CPU instead of refusing the model.
        val encodeAccelerators = if (backend == ParakeetBackend.CPU) {
            setOf(Accelerator.CPU)
        } else {
            setOf(backend.accelerator, Accelerator.CPU)
        }
        encodeModel = CompiledModel.create(encodeFile.absolutePath, CompiledModel.Options(encodeAccelerators), encodeEnvironment)
        // The decoder keeps the same model unless the NPU forced a second graph, in which case it is
        // the portable one on the CPU: its tiny per-token runs are exactly what an accelerator makes
        // slower.
        decodeEnvironment = if (sharedModel) null else Environment.create()
        decodeModel = if (sharedModel) {
            encodeModel
        } else {
            CompiledModel.create(decodeFile.absolutePath, CompiledModel.Options(Accelerator.CPU), decodeEnvironment!!)
        }

        val encodeBySignature = runCatching {
            encodeModel.createInputBuffers(ENCODE) to encodeModel.createOutputBuffers(ENCODE)
        }.getOrNull()
        encodeSignature = if (encodeBySignature != null) ENCODE else null
        encodeInputs = encodeBySignature?.first ?: encodeModel.createInputBuffers()
        encodeOutputs = encodeBySignature?.second ?: encodeModel.createOutputBuffers()
        encodeFeatureCount = inputType(INPUT_PREFIX + 0, ENCODE).elements()
        decodeInputs = decodeModel.createInputBuffers(DECODE)
        decodeOutputs = decodeModel.createOutputBuffers(DECODE)
        // Not every export has the stateful signature; without it the loop stops once the stateless
        // token slots are full, which still produces a usable window instead of failing the pass.
        decode1Inputs = runCatching { decodeModel.createInputBuffers(DECODE_1) }.getOrNull()
        decode1Outputs = runCatching { decodeModel.createOutputBuffers(DECODE_1) }.getOrNull()

        // The tensors are hidden-first: the encoder returns [1, 1024, 63] and the logits come back as
        // [1, 63, 4, 8198]. Those four numbers are the loop's whole geometry, and guessing them from
        // the flattened element count reads the time axis as 1024 frames instead of 63 - which still
        // produces tokens, just wrong ones, at sixteen times the work.
        val logitsDimensions = outputType(OUTPUT_PREFIX + 0, DECODE).dimensions()
        maxTimeIndex = logitsDimensions.getOrNull(1) ?: 0
        tokenSlots = logitsDimensions.getOrNull(2) ?: inputType(INPUT_PREFIX + 1, DECODE).elements()
        logitsPerToken = logitsDimensions.getOrNull(3) ?: 0
        statePairs = listOf(
            listOf(decodeInputs[2], decodeInputs[3]),
            listOf(decodeOutputs[1], decodeOutputs[2]),
        )
        stateElements = inputType(INPUT_PREFIX + 2, DECODE).elements()
        loadMs = SystemClock.elapsedRealtime() - startedAt
    }

    /** A tensor's type by signature, falling back to the default entry point. */
    private fun inputType(name: String, signature: String): TensorType = runCatching { decodeModel.getInputTensorType(name, signature) }.getOrNull() ?: decodeModel.getInputTensorType(name)

    private fun outputType(name: String, signature: String): TensorType = runCatching { decodeModel.getOutputTensorType(name, signature) }.getOrNull() ?: decodeModel.getOutputTensorType(name)

    /**
     * Recognises one window of features.
     *
     * [features] is [ParakeetMel]'s flat `[128][500]` block; it is trimmed or zero-padded to the
     * encoder's input size so a short final window is a legal one.
     */
    fun transcribe(features: FloatArray): Window {
        val encodeStartedAt = SystemClock.elapsedRealtime()
        encodeInputs[0].writeFloat(padOrTrim(features, encodeFeatureCount))
        val signature = encodeSignature
        if (signature != null) {
            encodeModel.run(encodeInputs, encodeOutputs, signature)
        } else {
            encodeModel.run(encodeInputs, encodeOutputs)
        }
        val encodeMs = SystemClock.elapsedRealtime() - encodeStartedAt

        val decodeStartedAt = SystemClock.elapsedRealtime()
        val tokens = ArrayList<Token>(32)
        decode(tokens)
        if (!reportedShapes) {
            reportedShapes = true
            // Worth a line per pass: a wrong tensor layout still produces tokens, and the shapes are
            // the only thing that says why they are the wrong ones.
            Timber.tag(ParakeetStats.TAG).d(
                "shapes: ${backend.label} encode in ${inputType(INPUT_PREFIX + 0, ENCODE).dimensions()} " +
                    "out ${inputType(INPUT_PREFIX + 0, DECODE).dimensions()}, " +
                    "decode args ${(0..3).map { inputType(INPUT_PREFIX + it, DECODE).dimensions() }} " +
                    "out ${(0..2).map { outputType(OUTPUT_PREFIX + it, DECODE).dimensions() }}, " +
                    "slots=$tokenSlots frames=$maxTimeIndex logits=$logitsPerToken, " +
                    "tokens=${tokens.take(12).map { "${it.id}@${it.frame}" }}",
            )
        }
        return Window(
            tokens = tokens,
            frames = maxTimeIndex,
            encodeMs = encodeMs,
            decodeMs = SystemClock.elapsedRealtime() - decodeStartedAt,
        )
    }

    /**
     * Greedy TDT decoding of [encodeOutputs].
     *
     * At each step the joint network's logits are split into a token id and a duration; the id is
     * emitted unless it is the blank, and the duration says how many encoder frames to skip. The
     * stateless signature computes several token slots at once, so the first few emissions ride
     * along until the slots are full, then the loop switches to the stateful signature.
     */
    private fun decode(tokens: MutableList<Token>) {
        if (maxTimeIndex <= 0 || logitsPerToken <= 0) return
        for (state in statePairs[stateIndex]) state.writeFloat(FloatArray(stateElements))

        // One copy of the encoder's output when the two halves are different graphs: 1,024 x 63
        // floats, a quarter of a megabyte per window, against the NPU dispatch cost of decoding
        // there. With one shared model the encoder's output buffers are the decoder's input buffers
        // and nothing is copied at all.
        val encoderOutput = if (sharedModel) {
            emptyList()
        } else {
            decodeInputs[0].writeFloat(encodeOutputs[0].readFloat())
            listOf(decodeInputs[0])
        }

        var signature = DECODE
        var tokenIdsBuffer = decodeInputs[1]
        var logitsBuffer = decodeOutputs[0]
        var slots = tokenSlots
        var tokenIds = IntArray(tokenSlots)
        var slot = 0
        tokenIds[0] = START_TOKEN_ID

        var timeIndex = 0
        var emissionsAtFrame = 0
        while (timeIndex < maxTimeIndex) {
            tokenIdsBuffer.writeInt(tokenIds)
            decodeModel.run(
                buildList {
                    addAll(if (sharedModel) encodeOutputs else encoderOutput)
                    add(tokenIdsBuffer)
                    addAll(statePairs[stateIndex])
                },
                buildList {
                    add(logitsBuffer)
                    addAll(statePairs[1 - stateIndex])
                },
                signature,
            )

            val logits = logitsBuffer.readFloat()
            val logitsAtFrame = timeIndex * slots * logitsPerToken
            val tokenStart = logitsAtFrame + slot * logitsPerToken
            val durationStart = tokenStart + logitsPerToken - DURATIONS
            val tokenId = argmax(logits, tokenStart, durationStart) - tokenStart
            val duration = argmax(logits, durationStart, tokenStart + logitsPerToken) - durationStart

            if (tokenId != blankTokenId) {
                tokens.add(Token(tokenId, timeIndex))
                if (slot + 1 < slots) {
                    // Room left in the stateless array: keep filling it, so the LSTM state handed to
                    // the stateful signature is computed from real tokens only.
                    slot++
                } else if (decode1Inputs != null && decode1Outputs != null) {
                    signature = DECODE_1
                    slots = 1
                    slot = 0
                    tokenIds = IntArray(1)
                    tokenIdsBuffer = decode1Inputs[1]
                    logitsBuffer = decode1Outputs[0]
                } else {
                    return
                }
                tokenIds[slot] = tokenId
            }

            // A token that repeats a frame is legal but cannot repeat forever; the cap keeps a
            // degenerate graph from parking the worker on one frame.
            emissionsAtFrame = if (duration <= 0) emissionsAtFrame + 1 else 0
            timeIndex += if (duration <= 0) 1 else duration
            if (emissionsAtFrame > MAX_EMISSIONS_PER_FRAME) timeIndex++

            if (slots == 1 && tokenId != blankTokenId) {
                // Greedy RNN-T advances the prediction network only on an emission; swapping on a
                // blank would feed the last token back in once per blank and degrade the text.
                stateIndex = 1 - stateIndex
            }
        }
    }

    override fun close() {
        if (!sharedModel) runCatching { decodeModel.close() }
        runCatching { encodeModel.close() }
        runCatching { decodeEnvironment?.close() }
        runCatching { encodeEnvironment.close() }
    }

    private fun argmax(values: FloatArray, from: Int, to: Int): Int {
        var best = from
        for (index in from until to) if (values[index] > values[best]) best = index
        return best
    }

    private fun padOrTrim(features: FloatArray, size: Int): FloatArray = when {
        features.size == size -> features

        // A short block is silence-padded at the end; a long one keeps its tail, which is where the
        // audio the window was built for actually is.
        features.size < size -> features.copyOf(size)

        else -> features.copyOfRange(features.size - size, features.size)
    }

    private fun TensorType.elements(): Int = layout?.dimensions?.fold(1, Int::times) ?: 0

    private fun TensorType.lastDimension(): Int = layout?.dimensions?.lastOrNull() ?: 0

    private fun TensorType.dimensions(): List<Int> = layout?.dimensions ?: emptyList()

    private companion object {
        const val ENCODE = "encode"
        const val DECODE = "decode"
        const val DECODE_1 = "decode_1"
        const val INPUT_PREFIX = "args_"
        const val OUTPUT_PREFIX = "output_"

        /** Parakeet's vocabulary is 8 192 pieces, and the blank is the id right after it. */
        const val BLANK_TOKEN_ID = 8_192
        const val START_TOKEN_ID = 8_192
        const val DURATIONS = 5
        const val MAX_EMISSIONS_PER_FRAME = 8
    }
}
