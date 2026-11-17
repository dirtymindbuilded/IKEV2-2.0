package app.ikev2manager.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Stats {
    data class Summary(
        val sent: Int,
        val received: Int,
        val avg: Double?,
        val min: Double?,
        val max: Double?,
        val lossPct: Double,
        val jitter: Double?,
    )

    fun summarize(samplesMs: List<Double>, sent: Int): Summary {
        val received = samplesMs.size
        val loss = if (sent <= 0) 100.0 else (sent - received).coerceAtLeast(0).toDouble() / sent * 100.0
        if (samplesMs.isEmpty()) return Summary(sent, 0, null, null, null, loss, null)
        val jitter = if (samplesMs.size < 2) 0.0 else samplesMs.zipWithNext { a, b -> abs(b - a) }.average()
        return Summary(sent, received, samplesMs.average(), samplesMs.min(), samplesMs.max(), loss, jitter)
    }

    /** 0..100. Penalises loss (2 points per %) and jitter relative to the average latency (max 40 points). */
    fun stability(s: Summary): Double {
        val avg = s.avg ?: return 0.0
        val jitterPenalty = min(40.0, (s.jitter ?: 0.0) / max(avg, 1.0) * 100.0)
        return (100.0 - s.lossPct * 2.0 - jitterPenalty).coerceIn(0.0, 100.0)
    }
}
