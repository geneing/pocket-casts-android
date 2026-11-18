package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import com.google.ai.edge.litert.Accelerator

/**
 * Which hardware the encoder runs on.
 *
 * Only the encoder is offered to an accelerator. The mel front end is a few milliseconds of work, and
 * the TDT decoder is 60-100 *single-token* runs per window, where the per-run dispatch an accelerator
 * pays costs more than the multiply-adds it replaces; both stay on the CPU on every backend.
 *
 * [CPU] runs the 4-bit encoder, which is the export this model was measured on and the default. [GPU]
 * and [NPU] run the float16 encoder, whose graph is not part of an install: a pass only asks for it
 * when pinned, so the plumbing stays reachable without making every phone store another gigabyte.
 */
enum class ParakeetBackend(val label: String, val accelerator: Accelerator) {
    NPU("npu", Accelerator.NPU),
    GPU("gpu", Accelerator.GPU),
    CPU("cpu", Accelerator.CPU),
    ;

    companion object {
        /**
         * The backend a pass uses unless it is pinned: the 4-bit encoder on the CPU.
         *
         * That is the pair `litert-community/parakeet-redux` measures as its CPU form, and the only
         * one whose graph a normal install carries. A pinned backend has to have the float16 encoder
         * pushed to the device first (`install-model.ps1 -WithF16`).
         */
        fun preferred(): ParakeetBackend = CPU

        /** The named backend, or null when the name is not one of these. */
        fun fromLabel(label: String?): ParakeetBackend? = entries.firstOrNull { it.label == label?.lowercase() }
    }
}
