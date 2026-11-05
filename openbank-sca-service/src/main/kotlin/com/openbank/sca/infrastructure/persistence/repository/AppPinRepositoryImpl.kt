// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.persistence.repository

import com.openbank.sca.application.usecase.AppPinRepository
import com.openbank.sca.domain.model.AppPin
import com.openbank.sca.infrastructure.persistence.entity.AppPinEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class AppPinRepositoryImpl : AppPinRepository {
    override suspend fun find(credentialId: String): AppPin? = Panache.withSession {
        Panache.getSession().flatMap { it.find(AppPinEntity::class.java, credentialId) }
    }.awaitSuspending()?.toDomain()

    /** `merge`, never `persist`: the credential id is application-assigned, and a PIN change is an UPDATE. */
    override suspend fun save(pin: AppPin) {
        Panache.withTransaction {
            Panache.getSession().flatMap { it.merge(AppPinEntity.from(pin)) }
        }.awaitSuspending()
    }
}
