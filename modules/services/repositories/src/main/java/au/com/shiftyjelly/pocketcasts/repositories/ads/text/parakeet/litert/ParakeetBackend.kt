package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.NpuCompatibilityChecker

/**
 * Which hardware the encoder and decoder run on.
 *
 * The three are not interchangeable on speed, which is the whole reason the choice exists: the TDT
 * decoder runs 60-90 tiny sequential invocations per window, and an accelerator that pays a queue
 * round trip for each of those loses to a CPU that does not. [NPU] is first because a Tensor G6 on
 * this build is what the LiteRT port was written for; [CPU] is the fallback that always works.
 */
enum class ParakeetBackend(val label: String, val accelerator: Accelerator) {
    NPU("npu", Accelerator.NPU),
    GPU("gpu", Accelerator.GPU),
    CPU("cpu", Accelerator.CPU),
    ;

    companion object {
        /**
         * The backend for a device that was not asked for one: the Google Tensor NPU when the SoC
         * and its runtime support it, and the CPU otherwise.
         *
         * The GPU is never picked by default even on phones that have one, because on every
         * measurement so far it is the slowest of the three for this model.
         */
        fun preferred(): ParakeetBackend = if (NpuCompatibilityChecker.GoogleTensor.isDeviceSupported()) NPU else CPU

        /** The named backend, or null when the name is not one of these. */
        fun fromLabel(label: String?): ParakeetBackend? = entries.firstOrNull { it.label == label?.lowercase() }
    }
}
