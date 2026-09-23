package cgbuildlogic

/**
 * A Maven version range, the way a mod descriptor spells one.
 *
 * <p>Ranges are what a merged jar's variants are chosen by, so two variants of one loader must not
 * both claim a version: the bootstrapper takes the first match, and an overlap makes that choice
 * depend on declaration order rather than on the range. {@link ModDescriptor} refuses one with this.</p>
 *
 * <pre>
 * McRange.parse("[1.20.1,1.21)").contains("1.20.4")                       // true
 * McRange.parse("[1.7.10]").contains("1.7.10")                            // true -- a bare version is exact
 * McRange.parse("[1.20.1,)")                                              // open above
 * McRange.parse("[1.20,1.20.2)").overlaps(McRange.parse("[1.20.2,1.21)")) // false -- adjacent, not overlapping
 * McRange.hull(listOf(a, b))                                              // the widest range covering both
 * </pre>
 *
 * Easy to get wrong: an unbounded end is `null` and is written with an exclusive bracket (`[1.20,)`),
 * and versions compare segment by segment as integers, so `1.9` sorts *below* `1.20`.
 */
data class McRange(
    val low: String?,
    val lowInclusive: Boolean,
    val high: String?,
    val highInclusive: Boolean,
) {

    fun contains(version: String): Boolean {
        if (low != null) {
            val c = compareVersions(version, low)
            if (c < 0 || (c == 0 && !lowInclusive)) return false
        }
        if (high != null) {
            val c = compareVersions(version, high)
            if (c > 0 || (c == 0 && !highInclusive)) return false
        }
        return true
    }

    /** Whether any version satisfies both. Adjacent ranges — one ending exactly where the next begins, exclusively — do not. */
    fun overlaps(other: McRange): Boolean = !endsBefore(this, other) && !endsBefore(other, this)

    /**
     * Fabric's spelling of this range for a `depends` entry — `>=1.20.4 <1.20.5`, a space meaning AND.
     * An exact version stays bare, and an unbounded range is `*`.
     */
    fun toFabricPredicate(): String {
        if (low != null && low == high && lowInclusive && highInclusive) return low
        val parts = mutableListOf<String>()
        low?.let { parts += (if (lowInclusive) ">=" else ">") + it }
        high?.let { parts += (if (highInclusive) "<=" else "<") + it }
        return parts.joinToString(" ").ifEmpty { "*" }
    }

    override fun toString(): String {
        if (low != null && low == high && lowInclusive && highInclusive) return "[$low]"
        val open = if (lowInclusive && low != null) "[" else "("
        val close = if (highInclusive && high != null) "]" else ")"
        return open + low.orEmpty() + "," + high.orEmpty() + close
    }

    companion object {

        fun parse(text: String): McRange {
            val t = text.trim()
            require(t.length >= 3) { "not a version range: $text" }
            val lowInclusive = when (t.first()) {
                '[' -> true
                '(' -> false
                else -> throw IllegalArgumentException("a version range opens with [ or (: $text")
            }
            val highInclusive = when (t.last()) {
                ']' -> true
                ')' -> false
                else -> throw IllegalArgumentException("a version range closes with ] or ): $text")
            }
            val inner = t.substring(1, t.length - 1)
            if (',' !in inner) {
                val exact = inner.trim()
                require(exact.isNotEmpty()) { "a version range with no version in it: $text" }
                require(lowInclusive && highInclusive) { "a single-version range is written [$exact]: $text" }
                return McRange(exact, true, exact, true)
            }
            val low = inner.substringBefore(',').trim().ifEmpty { null }
            val high = inner.substringAfter(',').trim().ifEmpty { null }
            require(low != null || high != null) { "a version range unbounded at both ends: $text" }
            return McRange(low, lowInclusive && low != null, high, highInclusive && high != null)
        }

        /**
         * The widest range covering every one of them.
         *
         * <p>A hull, not a set: a gap between two ranges is inside the result. That is what
         * `mods.toml` needs, which takes one range per dependency — and why the bootstrapper, not
         * this, is what refuses a version inside the hull that no variant claims.</p>
         */
        fun hull(ranges: List<McRange>): McRange {
            require(ranges.isNotEmpty()) { "no ranges to take a hull of" }
            var low = ranges[0].low
            var lowInclusive = ranges[0].lowInclusive
            var high = ranges[0].high
            var highInclusive = ranges[0].highInclusive
            for (r in ranges.drop(1)) {
                if (low != null) {
                    if (r.low == null) {
                        low = null
                        lowInclusive = false
                    } else {
                        val c = compareVersions(r.low, low)
                        if (c < 0) {
                            low = r.low
                            lowInclusive = r.lowInclusive
                        } else if (c == 0 && r.lowInclusive) {
                            lowInclusive = true
                        }
                    }
                }
                if (high != null) {
                    if (r.high == null) {
                        high = null
                        highInclusive = false
                    } else {
                        val c = compareVersions(r.high, high)
                        if (c > 0) {
                            high = r.high
                            highInclusive = r.highInclusive
                        } else if (c == 0 && r.highInclusive) {
                            highInclusive = true
                        }
                    }
                }
            }
            return McRange(low, lowInclusive, high, highInclusive)
        }

        private fun endsBefore(a: McRange, b: McRange): Boolean {
            if (a.high == null || b.low == null) return false
            val c = compareVersions(a.high, b.low)
            return c < 0 || (c == 0 && !(a.highInclusive && b.lowInclusive))
        }
    }
}

/** 1.20.1 before 1.20.4 before 1.21 — numeric per segment, so "1.9" does not beat "1.20". */
internal fun compareVersions(a: String, b: String): Int {
    val left = a.split('.')
    val right = b.split('.')
    for (i in 0 until maxOf(left.size, right.size)) {
        val l = left.getOrNull(i)?.toIntOrNull() ?: 0
        val r = right.getOrNull(i)?.toIntOrNull() ?: 0
        if (l != r) return l - r
    }
    return 0
}
