package kr.mom.probe.agent

/**
 * Central assistant identity contract.
 *
 * Kotlin callers must use [displayName] instead of a hard-coded name.
 * Android XML/manifest/widget strings cannot read this object at runtime,
 * so res/values mirrors it — [AgentIdentityTest] asserts the mirror stays
 * in sync. Stable identifiers (package kr.mom.*, mom-* notification
 * channel IDs, prefs keys, crypto tags, stored records) are never renamed.
 */
object AgentIdentity {
    const val ID = "ravi"
    const val displayName = "라비"
    const val MASCOT = "rabbit"

    /**
     * Wake names, longest first. 모모/모모야 stay valid for existing
     * users; 라비/라비야 are additive. Ordering matters: "모모야" must be
     * tried before "모모" so the suffix is not left behind.
     */
    val wakeNames = listOf("라비야", "모모야", "라비", "모모")

    /**
     * Removes a leading wake name only when it is followed by the end of
     * input or a non-letter/digit boundary, then trims following
     * whitespace/punctuation. "모모랜드" or "라비올리" are ordinary words
     * and pass through untouched.
     */
    fun stripWakeName(input: String): String {
        val text = input.trim()
        for (name in wakeNames) {
            if (!text.startsWith(name)) continue
            val rest = text.substring(name.length)
            if (rest.isEmpty()) return ""
            if (!rest.first().isLetterOrDigit()) {
                return rest.trimStart { !it.isLetterOrDigit() }
            }
        }
        return text
    }
}
