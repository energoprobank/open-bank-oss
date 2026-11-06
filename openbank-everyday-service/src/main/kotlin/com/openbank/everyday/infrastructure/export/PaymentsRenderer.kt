// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.everyday.infrastructure.export

import com.openbank.everyday.application.ExportRenderer
import com.openbank.everyday.domain.AccountRef
import com.openbank.everyday.domain.Txn
import jakarta.enterprise.context.ApplicationScoped
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** Payments as CSV (UTF-8 with a BOM, so spreadsheet apps read the accents) or as a one-font PDF. */
@ApplicationScoped
class PaymentsRenderer : ExportRenderer {

    override fun csv(rows: List<Txn>): ByteArray {
        val sb = StringBuilder("\uFEFF") // byte-order mark: spreadsheet apps then read UTF-8
        sb.append("date;direction;amount;currency;description;category\r\n")
        rows.forEach {
            sb.append(it.date).append(';')
                .append(if (it.outgoing) "OUT" else "IN").append(';')
                .append((if (it.outgoing) it.amount.negate() else it.amount).toPlainString()).append(';')
                .append(it.currency).append(';')
                .append(cell(it.description)).append(';')
                .append(it.category ?: "").append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** A CSV cell: quoted when it carries the separator, a quote or a line break; a formula prefix neutralised. */
    private fun cell(s: String): String {
        val safe = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s
        return if (safe.any { it in SPECIAL }) {
            "\"" + safe.replace("\"", "\"\"") +
                "\""
        } else {
            safe
        }
    }

    override fun pdf(account: AccountRef, from: LocalDate, to: LocalDate, rows: List<Txn>): ByteArray =
        PDDocument().use { doc ->
            val font = javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf")!!.use { PDType0Font.load(doc, it) }
            var page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            var out = PDPageContentStream(doc, page)
            var y = TOP
            fun line(text: String, size: Float = BODY) {
                if (y < BOTTOM) {
                    out.close()
                    page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    out = PDPageContentStream(doc, page)
                    y = TOP
                }
                out.beginText()
                out.setFont(font, size)
                out.newLineAtOffset(LEFT, y)
                out.showText(text.take(MAX_CHARS))
                out.endText()
                y -= size + LEADING
            }
            line("Energo-Pro Bank — payments", TITLE)
            line("${account.iban}  ·  ${account.currency}  ·  $from – $to")
            line("")
            rows.forEach {
                val sign = if (it.outgoing) "−" else "+"
                line(
                    "${it.date}   $sign${it.amount.toPlainString()} ${it.currency}   ${it.description.replace(
                        '\n',
                        ' ',
                    )}",
                )
            }
            if (rows.isEmpty()) line("No payments in this period.")
            out.close()
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    private companion object {
        const val SPECIAL = ";\"\n\r"
        const val TOP = 800f
        const val BOTTOM = 50f
        const val LEFT = 40f
        const val TITLE = 16f
        const val BODY = 9f
        const val LEADING = 4f
        const val MAX_CHARS = 120
    }
}
