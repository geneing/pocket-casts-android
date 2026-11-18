package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import com.google.ai.edge.litert.Accelerator

/**
 * Which hardware the encoder and decoder run on.
 *
 * The three are not interchangeable on speed, which is the whole reason the choice exists: the TDT
 * decoder runs 60-90 tiny sequential invocations per window, and an accelerator that pays a queue
 * round trip for each of those loses to a CPU that does not. [CPU] is the default because it measured
 * fastest end to end on the Pixel 11 (rtf 5.06 against the NPU's 2.97 and the GPU's 0.47); [NPU] is
 * kept because its encoder was twice the CPU's and that advantage should grow on long episodes.
 */
enum class ParakeetBackend(val label: String, val accelerator: Accelerator) {
    NPU("npu", Accelerator.NPU),
    GPU("gpu", Accelerator.GPU),
    CPU("cpu", Accelerator.CPU),
    ;

    companion object {
        /**
         * The backend a pass uses unless it is pinned: always the portable graph on the CPU.
         *
         * The NPU is not picked even on a Google Tensor phone. Over a 33.6 s clip it was slower for
         * the whole pass (rtf 2.97 against 5.06) because only the encoder can use it: the decoder's
         * per-token dispatch and the CPU-only mel front end decide the pass, and the NPU also pays a
         * two-graph load. That is the measurement to beat before this returns [NPU].
         */
        fun preferred(): ParakeetBackend = CPU

        /** The named backend, or null when the name is not one of these. */
        fun fromLabel(label: String?): ParakeetBackend? = entries.firstOrNull { it.label == label?.lowercase() }
    }
}
