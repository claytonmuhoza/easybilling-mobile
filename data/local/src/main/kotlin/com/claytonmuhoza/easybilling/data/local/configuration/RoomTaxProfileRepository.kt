package com.claytonmuhoza.easybilling.data.local.configuration

import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion

/** [TaxProfileRepository] backed by the local database. */
internal class RoomTaxProfileRepository(private val dao: TaxProfileDao) : TaxProfileRepository {

    override suspend fun current(): TaxProfileVersion? = dao.current()?.toDomain()

    override suspend fun history(): List<TaxProfileVersion> = dao.history().map { it.toDomain() }

    override suspend fun find(number: Int): TaxProfileVersion? = dao.find(number)?.toDomain()

    override suspend fun append(version: TaxProfileVersion) = dao.insert(version.toEntity())
}
