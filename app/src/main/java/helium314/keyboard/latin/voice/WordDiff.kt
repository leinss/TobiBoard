// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Minimal word-level diff used to highlight what a text-fix changed before the user accepts it.
 * Tokenizes on whitespace boundaries (keeping the separators so spacing renders intact), runs a
 * classic LCS, and emits an ordered list of segments tagged COMMON / ADDED / REMOVED.
 *
 * Pure and allocation-bounded: above [MAX_TOKENS] tokens the O(n*m) table would be too large for an
 * IME, so we bail to a single COMMON segment (the overlay then just shows the proposed text plainly).
 */
internal object WordDiff {
    enum class Op { COMMON, ADDED, REMOVED }
    data class Segment(val text: String, val op: Op)

    // 800*800 ints ≈ 2.5 MB transient — the ceiling we accept on the input thread's heap.
    private const val MAX_TOKENS = 800

    private val TOKEN_REGEX = Regex("\\S+|\\s+")

    fun diff(original: String, proposed: String): List<Segment> {
        if (original == proposed) {
            return if (proposed.isEmpty()) emptyList() else listOf(Segment(proposed, Op.COMMON))
        }
        val a = tokenize(original)
        val b = tokenize(proposed)
        if (a.size > MAX_TOKENS || b.size > MAX_TOKENS) {
            // Too large to diff cheaply; fall back to showing the proposed text without highlights.
            return if (proposed.isEmpty()) emptyList() else listOf(Segment(proposed, Op.COMMON))
        }
        val n = a.size
        val m = b.size
        // lcs[i][j] = length of the longest common subsequence of a[i..] and b[j..].
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = ArrayList<Segment>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(Segment(a[i], Op.COMMON)); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out.add(Segment(a[i], Op.REMOVED)); i++ }
                else -> { out.add(Segment(b[j], Op.ADDED)); j++ }
            }
        }
        while (i < n) { out.add(Segment(a[i], Op.REMOVED)); i++ }
        while (j < m) { out.add(Segment(b[j], Op.ADDED)); j++ }
        return mergeAdjacent(out)
    }

    private fun tokenize(s: String): List<String> = TOKEN_REGEX.findAll(s).map { it.value }.toList()

    /** Coalesce runs of the same Op so the rendered spans (and tests) stay compact. */
    private fun mergeAdjacent(segments: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>(segments.size)
        for (seg in segments) {
            val last = out.lastOrNull()
            if (last != null && last.op == seg.op) {
                out[out.size - 1] = last.copy(text = last.text + seg.text)
            } else {
                out.add(seg)
            }
        }
        return out
    }
}
