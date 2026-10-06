package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.PermissionPolicy
import com.claytonmuhoza.easybilling.domain.identity.UserRepository
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountLimits
import com.claytonmuhoza.easybilling.domain.invoicing.DiscountSettingsRepository
import com.claytonmuhoza.easybilling.domain.invoicing.SaveDiscountSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Builds the cap of the discount of the cashier and its check (UC-C03, D50). */
@Module
@InstallIn(SingletonComponent::class)
object DiscountModule {

    /** @return the cap of the discount kept in the local database. */
    @Provides
    fun discountSettingsRepository(storage: LocalStorage): DiscountSettingsRepository = storage.discountSettings

    /** @return the check of the discounts that need the approval of a manager. */
    @Provides
    fun discountLimits(
        users: UserRepository,
        settings: DiscountSettingsRepository,
        policy: PermissionPolicy,
    ): DiscountLimits = DiscountLimits(users, settings, policy)

    /** @return the use case saving the cap of the discount of the cashier. */
    @Provides
    fun saveDiscountSettings(
        access: AccessControl,
        settings: DiscountSettingsRepository,
        auditLog: AuditLog,
    ): SaveDiscountSettings = SaveDiscountSettings(access, settings, auditLog)
}
