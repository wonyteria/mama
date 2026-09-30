package kr.mom.probe.data

/**
 * Canonical cross-lane notice grouping. Raw captures stay per-lane records; grouping is computed
 * from shared identifiers so one notice arriving through several lanes is one group while every
 * source reference is preserved on the member records.
 *
 * Identifier order follows DESIGN.md §6: official IDs/URLs first, then institution + external ID,
 * then institution + normalized title + event/deadline date + body fingerprint. Title alone never
 * merges two records, and a content fingerprint never merges records that carry disjoint official
 * identifiers.
 *
 * Unprefixed keys are raw digests (notification identity, source item identity) so identities
 * already stored on tasks and tombstones keep matching. New key families carry a prefix:
 * `url:` item-specific canonical URL, `ext:` institution host + external ID, `fp:` content
 * fingerprint.
 */
object NoticeGrouping {
    private val whitespace = Regex("\\s+")
    private const val BODY_FINGERPRINT_CHARS = 160
    private const val FP_PREFIX = "fp:"
    private const val URL_PREFIX = "url:"
    private const val EXT_PREFIX = "ext:"

    /** The institution a group key is scoped to. All lanes serve the configured child's school. */
    fun institution(settings: ProbeSettings): String = normalize(settings.schoolName)

    /** Every identifier this record shares with another copy or revision of the same notice. */
    fun keys(record: ProbeRecord, institution: String): Set<String> {
        val keys = mutableSetOf(ProbeRules.notificationIdentity(record.packageName, record.notificationKey))
        record.sourceMetadata?.let { metadata ->
            keys += ProbeRules.sourceItemIdentity(metadata.sourceId, metadata.itemId)
            val rawId = metadata.origin.rawId?.takeUnless { it.isBlank() } ?: metadata.itemId
            val canonicalUrl = metadata.origin.canonicalUrl.trim()
            if (canonicalUrl.isNotBlank() && rawId.isNotBlank() && canonicalUrl.contains(rawId)) {
                keys += URL_PREFIX + ProbeRules.digest(canonicalUrl.lowercase())
            }
            if (rawId.isNotBlank()) {
                keys += EXT_PREFIX + ProbeRules.digest("${metadata.origin.host.lowercase()} $rawId")
            }
        }
        fingerprintKey(record, institution)?.let { keys += it }
        return keys
    }

    /**
     * Canonical per-record group label. The content fingerprint is preferred because it is the
     * only identifier every lane can produce, so app and web copies of one notice share it.
     * Membership decisions always use [keys] intersection or [groupIds].
     */
    fun groupId(record: ProbeRecord, institution: String): String {
        val recordKeys = keys(record, institution)
        return fingerprintKey(record, institution)?.takeIf { it in recordKeys } ?: recordKeys.min()
    }

    /** Maps each record id to its canonical group id, merging records that share any key. */
    fun groupIds(records: List<ProbeRecord>, institution: String): Map<String, String> {
        val parent = IntArray(records.size) { it }
        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) root = parent[root]
            return root
        }
        fun union(a: Int, b: Int) {
            val (ra, rb) = find(a) to find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val recordKeys = records.map { keys(it, institution) }
        val keyOwner = mutableMapOf<String, Int>()
        val fingerprintOwner = mutableMapOf<String, MutableList<Int>>()
        recordKeys.forEachIndexed { index, keys ->
            keys.forEach { key ->
                if (key.startsWith(FP_PREFIX)) {
                    fingerprintOwner.getOrPut(key) { mutableListOf() } += index
                } else {
                    keyOwner[key]?.let { union(index, it) } ?: run { keyOwner[key] = index }
                }
            }
        }
        // A fingerprint bucket may contain an app-only copy without official identifiers.
        // Do not let that keyless record bridge two different official documents transitively.
        fingerprintOwner.values.forEach { indices ->
            val roots = indices.map(::find).distinct()
            fun componentStrongKeys(root: Int): Set<String> = recordKeys.indices
                .filter { find(it) == root }
                .flatMapTo(mutableSetOf()) { strongKeys(recordKeys[it]) }
            val strongRoots = roots.filter { componentStrongKeys(it).isNotEmpty() }
            if (strongRoots.size <= 1) {
                roots.drop(1).forEach { union(roots.first(), it) }
            } else {
                // Ambiguous keyless copies remain separate from every official document.
                val keylessRoots = roots.filter { componentStrongKeys(it).isEmpty() }
                keylessRoots.drop(1).forEach { union(keylessRoots.first(), it) }
            }
        }
        val componentKeys = mutableMapOf<Int, String>()
        recordKeys.forEachIndexed { index, keys ->
            keys.forEach { key -> componentKeys.merge(find(index), key) { old, new -> minOf(old, new) } }
        }
        return records.mapIndexed { index, record -> record.id to componentKeys.getValue(find(index)) }.toMap()
    }

    /** One record per notice group: the member with the strongest available body/evidence. */
    fun representatives(records: List<ProbeRecord>, institution: String): List<ProbeRecord> {
        val groups = groupIds(records, institution)
        return records.groupBy { groups.getValue(it.id) }.values.map { members ->
            members.maxWith(
                compareBy<ProbeRecord> { it.sourceMetadata?.contentState == NoticeContentState.VERIFIED }
                    .thenBy { bodyText(it).length }
                    .thenBy { it.receivedAt }
            )
        }
    }

    /**
     * True when a record's group keys match keys already stored on a task. A fingerprint-only
     * match is rejected when both sides carry disjoint official identifiers.
     */
    fun matches(recordKeys: Set<String>, storedKeys: Set<String>): Boolean {
        val shared = recordKeys.intersect(storedKeys)
        if (shared.any { !it.startsWith(FP_PREFIX) }) return true
        if (shared.isEmpty()) return false
        return strongKeys(recordKeys).isEmpty() || strongKeys(storedKeys).isEmpty()
    }

    /** True when the record shares any group key with a previously linked task. */
    fun linked(record: ProbeRecord, institution: String, linkedKeys: Set<String>): Boolean =
        matches(keys(record, institution), linkedKeys)

    private fun strongKeys(keys: Set<String>): Set<String> =
        keys.filterTo(mutableSetOf()) { it.startsWith(URL_PREFIX) || it.startsWith(EXT_PREFIX) }

    private fun fingerprintKey(record: ProbeRecord, institution: String): String? {
        val title = normalize(record.title)
        val body = normalize(bodyText(record))
        if (title.isEmpty() || body.isEmpty()) return null
        return FP_PREFIX + ProbeRules.digest(listOf(
            institution,
            title,
            primaryDateIso(record).orEmpty(),
            ProbeRules.digest(body.take(BODY_FINGERPRINT_CHARS)),
        ).joinToString(" "))
    }

    private fun primaryDateIso(record: ProbeRecord): String? {
        val dates = record.sourceMetadata?.dateFacts?.map { it.role to it.dateIso }
            ?: NoticeDecisionEngine.decide(record).dates.map { it.role to it.dateIso }
        return listOf(NoticeDateRole.DUE, NoticeDateRole.EVENT, NoticeDateRole.APPLICATION_END)
            .firstNotNullOfOrNull { role -> dates.firstOrNull { it.first == role && it.second != null }?.second }
            ?: dates.firstOrNull { it.second != null }?.second
    }

    private fun bodyText(record: ProbeRecord): String =
        record.bigText.ifBlank { record.text }.ifBlank { record.textLines.joinToString(" ") }

    private fun normalize(value: String): String =
        value.replace(whitespace, " ").trim().lowercase()
}
