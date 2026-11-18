package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import android.content.Context
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetStats
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import timber.log.Timber

/**
 * Parakeet Redux TDT on LiteRT: one window of 16 kHz audio in, token ids out.
 *
 * The Redux export splits the model into three graphs where the earlier `.litertlm` bundle had one,
 * and the split is what the pipeline below is shaped by:
 *
 * - `mel`, a small CPU graph that turns the window's samples into `[128, T]` features and, more
 *   importantly, returns the masks that say how much of the window is real audio. The mel front end
 *   used to be Kotlin over jLibrosa; running it in the graph removes seconds per pass and is the
 *   only version that matches the weights it feeds.
 * - `encoder`, the FastConformer, which is the only part worth an accelerator: one run per window
 *   and the largest share of the model's flops.
 * - `decoder`, the TDT prediction network (`predict`) and joint network (`joint`), always on the CPU.
 *   Decoding is 60-100 single-token invocations per window, and per-token accelerator dispatch costs
 *   more than the multiply-adds it replaces.
 *
 * The decoder is a pair of signatures rather than one graph: `predict` advances the prediction
 * network's LSTM one token, `joint` combines one encoder frame with the prediction network's output
 * and returns 8 198 logits — 8 193 token logits (8 192 is the blank) and 5 duration logits.
 */
internal class ParakeetRecognizer(
    context: Context,
    melFile: File,
    /**
     * The encoder graph. The CPU runs the int4 export; the float16 export is what the GPU and NPU
     * can use, so a pinned backend asks for its own file.
     */
    encoderFile: File,
    /**
     * The prediction and joint networks. Always the int8 CPU graph: its runs are tiny and strictly
     * sequential, which is the one shape accelerators are worse at than the CPU.
     */
    decoderFile: File,
    val backend: ParakeetBackend,
) : AutoCloseable {

    /** One recognised token and the encoder frame it starts at. */
    internal data class Token(val id: Int, val frame: Int)

    internal data class Window(
        val tokens: List<Token>,
        val frames: Int,
        val melMs: Long,
        val encodeMs: Long,
        val decodeMs: Long,
    )

    /** How long the runtime took to open the three graphs; the first pass on a phone pays for it. */
    val loadMs: Long

    /** Samples the mel graph holds: one window of the model, 160 000 for the 10 s export. */
    val windowSamples: Int

    /** Encoder frames a full window produces, 125 for the 10 s export, one every 80 ms. */
    val windowFrames: Int

    private val environment: Environment
    private val mel: CompiledModel
    private val encoder: CompiledModel
    private val decoder: CompiledModel

    private val melInputs: Map<String, TensorBuffer>
    private val melOutputs: Map<String, TensorBuffer>
    private val audioValues: FloatArray
    private val audio: TensorBuffer
    private val validSamples: TensorBuffer
    private val features: TensorBuffer
    private val melMask: TensorBuffer
    private val frameMask: TensorBuffer

    private val encoderInputs: Map<String, TensorBuffer>
    private val encoderOutputs: Map<String, TensorBuffer>
    private val encoderFeatures: TensorBuffer
    private val encoderMask: TensorBuffer
    private val enc: TensorBuffer
    private val encFrame: TensorBuffer
    private val encFrameValues = FloatArray(HIDDEN)

    private val tokenBuffer: TensorBuffer
    private val decOut: TensorBuffer
    private val logits: TensorBuffer

    /**
     * The prediction network's LSTM state, as two buffer pairs: `predict` reads one and writes the
     * other, then the pairs swap. Swapping rather than copying means a run's output is the next
     * run's input, which matters over the hundreds of steps a window takes.
     */
    private val states: List<Pair<TensorBuffer, TensorBuffer>>
    private var stateIndex = 0

    /** Float count of one LSTM state tensor, which is all zeros when a window starts. */
    private val stateElements: Int

    /** The first window's geometry, logged once because a wrong tensor layout still yields tokens. */
    private var reportedShapes = false

    init {
        val startedAt = SystemClock.elapsedRealtime()
        environment = if (backend == ParakeetBackend.NPU) {
            Environment.create(BuiltinNpuAcceleratorProvider(context))
        } else {
            Environment.create()
        }
        // The mel front end and the decoder are small and sequential; only the encoder is offered to
        // the pinned accelerator, with the CPU as its fallback so a partially delegated graph still
        // runs the ops the accelerator could not take.
        mel = CompiledModel.create(melFile.absolutePath, CompiledModel.Options(Accelerator.CPU), environment)
        val encoderAccelerators = if (backend == ParakeetBackend.CPU) {
            setOf(Accelerator.CPU)
        } else {
            setOf(backend.accelerator, Accelerator.CPU)
        }
        encoder = CompiledModel.create(encoderFile.absolutePath, CompiledModel.Options(encoderAccelerators), environment)
        decoder = CompiledModel.create(decoderFile.absolutePath, CompiledModel.Options(Accelerator.CPU), environment)

        audio = mel.createInputBuffer(AUDIO)
        validSamples = mel.createInputBuffer(N_VALID)
        features = mel.createOutputBuffer(FEATURES)
        melMask = mel.createOutputBuffer(MEL_MASK)
        frameMask = mel.createOutputBuffer(FRAME_MASK)
        melInputs = mapOf(AUDIO to audio, N_VALID to validSamples)
        melOutputs = mapOf(FEATURES to features, MEL_MASK to melMask, FRAME_MASK to frameMask)
        windowSamples = inputElements(mel, AUDIO)
        audioValues = FloatArray(windowSamples)

        encoderFeatures = encoder.createInputBuffer(FEATURES)
        encoderMask = encoder.createInputBuffer(MEL_MASK)
        encoderInputs = mapOf(FEATURES to encoderFeatures, MEL_MASK to encoderMask)
        enc = encoder.createOutputBuffer(ENC)
        encoderOutputs = mapOf(ENC to enc)
        windowFrames = outputElements(mel, FRAME_MASK)

        tokenBuffer = decoder.createInputBuffer(TOKEN, PREDICT)
        decOut = decoder.createOutputBuffer(DEC_OUT, PREDICT)
        logits = decoder.createOutputBuffer(LOGITS, JOINT)
        encFrame = decoder.createInputBuffer(ENC_FRAME, JOINT)
        states = listOf(
            decoder.createInputBuffer(H, PREDICT) to decoder.createInputBuffer(C, PREDICT),
            decoder.createOutputBuffer(H_OUT, PREDICT) to decoder.createOutputBuffer(C_OUT, PREDICT),
        )
        stateElements = inputElements(decoder, H, PREDICT)

        loadMs = SystemClock.elapsedRealtime() - startedAt
    }

    /**
     * Recognises one window.
     *
     * [samples] is mono 16 kHz audio, at most [windowSamples] long; a shorter tail is zero padded and
     * its real length is passed to the mel graph, so the encoder's mask keeps the padding out of the
     * words rather than being heard as silence.
     */
    fun transcribe(samples: FloatArray): Window {
        val melStartedAt = SystemClock.elapsedRealtime()
        val valid = minOf(samples.size, windowSamples)
        audioValues.fill(0f)
        samples.copyInto(audioValues, destinationOffset = 0, startIndex = 0, endIndex = valid)
        audio.writeFloat(audioValues)
        validSamples.writeInt(intArrayOf(valid))
        mel.run(melInputs, melOutputs)
        val featureValues = features.readFloat()
        val maskValues = melMask.readFloat()
        val validFrames = frameMask.readFloat().count { it > 0.5f }
        encoderFeatures.writeFloat(featureValues)
        encoderMask.writeFloat(maskValues)
        val melMs = SystemClock.elapsedRealtime() - melStartedAt

        val encodeStartedAt = SystemClock.elapsedRealtime()
        encoder.run(encoderInputs, encoderOutputs)
        val encoded = enc.readFloat()
        val encodeMs = SystemClock.elapsedRealtime() - encodeStartedAt

        val decodeStartedAt = SystemClock.elapsedRealtime()
        val tokens = ArrayList<Token>(32)
        decode(encoded, validFrames, tokens)
        if (!reportedShapes) {
            reportedShapes = true
            // Worth a line per pass: a wrong tensor layout still produces tokens, and the shapes are
            // the only thing that says why they are the wrong ones.
            Timber.tag(ParakeetStats.TAG).d(
                "shapes: ${backend.label} mel ${mel.getInputTensorType(AUDIO).dimensions()} -> " +
                    "${mel.getOutputTensorType(FEATURES).dimensions()}, " +
                    "encoder ${encoder.getInputTensorType(FEATURES).dimensions()} -> " +
                    "${encoder.getOutputTensorType(ENC).dimensions()}, " +
                    "predict ${decoder.getInputTensorType(TOKEN, PREDICT).dimensions()} -> " +
                    "${decoder.getOutputTensorType(DEC_OUT, PREDICT).dimensions()}, " +
                    "joint ${decoder.getInputTensorType(ENC_FRAME, JOINT).dimensions()} -> " +
                    "${decoder.getOutputTensorType(LOGITS, JOINT).dimensions()}, " +
                    "samples=$windowSamples frames=$windowFrames valid=$validFrames, " +
                    "tokens=${tokens.take(12).map { "${it.id}@${it.frame}" }}",
            )
        }
        return Window(
            tokens = tokens,
            frames = validFrames,
            melMs = melMs,
            encodeMs = encodeMs,
            decodeMs = SystemClock.elapsedRealtime() - decodeStartedAt,
        )
    }

    /**
     * Greedy TDT decoding of [encoded], for the first [validFrames] encoder frames.
     *
     * At each step the joint network's logits are split into a token id and a duration: a non-blank
     * token is emitted and fed back into `predict`, and the duration says how many encoder frames to
     * skip. A blank with duration 0 means "stay on this frame" for one step, and ten tokens on one
     * frame force a step forward so a degenerate graph cannot park the loop.
     */
    private fun decode(encoded: FloatArray, frames: Int, tokens: MutableList<Token>) {
        // The mel graph's frame_mask is the stop point, but the encoder's own output is what the
        // slicing below indexes: a shorter output than the mask says is a mismatched export, and
        // clamping turns it into a short window instead of an exception on every pass.
        val validFrames = minOf(frames, encoded.size / HIDDEN)
        if (validFrames <= 0) return
        stateIndex = 0
        states[stateIndex].first.writeFloat(FloatArray(stateElements))
        states[stateIndex].second.writeFloat(FloatArray(stateElements))
        predict(BLANK)

        var frame = 0
        var emittedAtFrame = 0
        var steps = 0
        val maxSteps = MAX_SYMBOLS_PER_FRAME * windowFrames
        while (frame < validFrames && steps < maxSteps) {
            steps++
            val offset = frame * HIDDEN
            encoded.copyInto(encFrameValues, startIndex = offset, endIndex = offset + HIDDEN)
            encFrame.writeFloat(encFrameValues)
            decoder.run(
                mapOf(ENC_FRAME to encFrame, DEC_OUT to decOut),
                mapOf(LOGITS to logits),
                JOINT,
            )
            val values = logits.readFloat()
            val token = argmax(values, 0, TOKEN_LOGITS)
            var duration = argmax(values, TOKEN_LOGITS, TOKEN_LOGITS + DURATIONS) - TOKEN_LOGITS

            if (token == BLANK && duration == 0) duration = 1
            if (token != BLANK) {
                // The end-of-window token is a control id, not speech; stopping is all it means.
                if (token == END_OF_WINDOW) return
                tokens.add(Token(token, frame))
                predict(token)
            }
            if (duration == 0) {
                if (token != BLANK) emittedAtFrame++
                if (emittedAtFrame >= MAX_SYMBOLS_PER_FRAME) {
                    emittedAtFrame = 0
                    duration = 1
                }
            } else {
                emittedAtFrame = 0
            }
            frame += duration
        }
    }

    /** Advances the prediction network one token; the state buffers swap so nothing is copied. */
    private fun predict(token: Int) {
        tokenBuffer.writeInt(intArrayOf(token))
        decoder.run(
            mapOf(
                TOKEN to tokenBuffer,
                H to states[stateIndex].first,
                C to states[stateIndex].second,
            ),
            mapOf(
                DEC_OUT to decOut,
                H_OUT to states[1 - stateIndex].first,
                C_OUT to states[1 - stateIndex].second,
            ),
            PREDICT,
        )
        stateIndex = 1 - stateIndex
    }

    override fun close() {
        runCatching { decoder.close() }
        runCatching { encoder.close() }
        runCatching { mel.close() }
        runCatching { environment.close() }
    }

    /** The first maximum, so a tie resolves the same way as the reference runner's argmax. */
    private fun argmax(values: FloatArray, from: Int, to: Int): Int {
        var best = from
        for (index in from until to) if (values[index] > values[best]) best = index
        return best
    }

    private fun inputElements(model: CompiledModel, name: String, signature: String = ""): Int = model.getInputTensorType(name, signature).elements()

    private fun outputElements(model: CompiledModel, name: String, signature: String = ""): Int = model.getOutputTensorType(name, signature).elements()

    private fun TensorType.elements(): Int = layout?.dimensions?.fold(1, Int::times) ?: 0

    private fun TensorType.dimensions(): List<Int> = layout?.dimensions ?: emptyList()

    private companion object {
        const val AUDIO = "audio"
        const val N_VALID = "n_valid"
        const val FEATURES = "features"
        const val MEL_MASK = "mel_mask"
        const val FRAME_MASK = "frame_mask"
        const val ENC = "enc"

        const val PREDICT = "predict"
        const val JOINT = "joint"
        const val TOKEN = "token"
        const val H = "h"
        const val C = "c"
        const val DEC_OUT = "dec_out"
        const val H_OUT = "h_out"
        const val C_OUT = "c_out"
        const val ENC_FRAME = "enc_frame"
        const val LOGITS = "logits"

        /** Vocabulary size of one logit row: 8 192 pieces plus the blank at id 8 192. */
        const val TOKEN_LOGITS = 8_193
        const val BLANK = 8_192
        const val DURATIONS = 5

        /** The encoder's hidden size, and one row of its output. */
        const val HIDDEN = 640

        /** A control token that ends a window; it is not text and is not emitted. */
        const val END_OF_WINDOW = 3

        /** Tokens one frame may emit before the loop is forced to advance. */
        const val MAX_SYMBOLS_PER_FRAME = 10
    }
}
