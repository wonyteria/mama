package kr.mom.probe.data

import android.content.Context
import kr.mom.probe.sync.IngestReceipt
import kr.mom.probe.sync.SourceFetchResult
import kr.mom.probe.sync.SourceIngestor
import kr.mom.probe.sync.SourceScope

class ProbeSourceIngestor(context: Context) : SourceIngestor {
    private val repository = ProbeRepository.get(context.applicationContext)

    override suspend fun ingest(scope: SourceScope, result: SourceFetchResult): IngestReceipt =
        repository.ingestSource(scope, result)
}
