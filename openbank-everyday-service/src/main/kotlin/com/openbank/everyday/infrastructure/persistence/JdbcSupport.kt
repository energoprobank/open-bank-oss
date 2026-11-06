// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.persistence

import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Plain blocking JDBC over the datasource Flyway uses. Every caller is a `@Blocking` REST endpoint, a
 * `@Blocking` Kafka handler or a scheduled job — never a Vert.x event loop — so no reactive session
 * (and no Vert.x context) is needed.
 */
open class JdbcSupport(private val dataSource: DataSource) {
    protected fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    protected fun update(sql: String, vararg args: Any?): Int = withConnection { c ->
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }
    }

    protected fun <T> queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = withConnection { c ->
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }
    }

    protected fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = withConnection { c ->
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += map(rs)
                out
            }
        }
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setObject(i + 1, null)
                is Instant -> ps.setTimestamp(i + 1, Timestamp.from(a))
                is Enum<*> -> ps.setString(i + 1, a.name)
                is UUID -> ps.setObject(i + 1, a)
                is BigDecimal -> ps.setBigDecimal(i + 1, a)
                else -> ps.setObject(i + 1, a)
            }
        }
    }

    protected fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

    protected fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
}
