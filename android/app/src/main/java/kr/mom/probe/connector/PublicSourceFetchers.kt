package kr.mom.probe.connector

import kr.mom.probe.sync.SourceFetcherRegistry
import kr.mom.probe.sync.SourceIds

object PublicSourceFetchers {
    fun registerDefaults() {
        SourceFetcherRegistry.register(SourceIds.SCHOOL_WEBSITE, SchoolWebsiteClient())
    }
}
