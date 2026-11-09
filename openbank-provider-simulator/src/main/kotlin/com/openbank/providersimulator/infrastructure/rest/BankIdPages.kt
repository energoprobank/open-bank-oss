// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.providersimulator.infrastructure.rest

import com.openbank.providersimulator.domain.AuthorizationRequest
import com.openbank.providersimulator.domain.BankIdIdentity

/**
 * The Bank iD pages a person sees: plain server-rendered HTML, no script, Czech with English. The
 * confirm button carries id `bankid-confirm` and the label "Potvrdit / Confirm" so a UI test inside
 * a WKWebView can find it. Paths are relative, so the page works under whatever host serves it.
 */
object BankIdPages {
    /** Czech banks offered in the picker (bank code to name); the first is preselected. */
    val BANKS = listOf(
        "0800" to "Česká spořitelna",
        "0300" to "ČSOB",
        "0100" to "Komerční banka",
        "5500" to "Raiffeisenbank",
        "0600" to "MONETA Money Bank",
        "3030" to "Air Bank",
        "2010" to "Fio banka",
        "6210" to "mBank",
    )

    fun consent(request: AuthorizationRequest, signedInAs: BankIdIdentity): String {
        val banks = BANKS.mapIndexed { i, (code, name) ->
            val checked = if (i == 0) " checked" else ""
            """<label class="bank"><input type="radio" name="bank" value="$code"$checked> ${escape(name)}</label>"""
        }.joinToString("\n")
        val who = escape("${signedInAs.givenName} ${signedInAs.familyName}")
        return page(
            "Bank iD",
            """
            <h1>Bank iD</h1>
            <p class="sub">Ověření identity přes vaši banku · <span lang="en">Identity verification through your bank</span></p>
            <p>Služba <strong>${escape(request.clientId)}</strong> žádá o tyto údaje: jméno a příjmení, datum narození,
            pohlaví, rodné číslo, státní občanství.<br>
            <span lang="en">${escape(
                request.clientId,
            )} asks for: name, date of birth, sex, birth number, nationality.</span></p>
            <form method="post" action="auth/confirm">
              <input type="hidden" name="request_id" value="${escape(request.requestId)}">
              <fieldset>
                <legend>Vyberte svou banku / <span lang="en">Choose your bank</span></legend>
                $banks
              </fieldset>
              <p class="who">Přihlášen(a) jako / <span lang="en">Signed in as</span> <strong id="bankid-person">$who</strong></p>
              <button type="submit" id="bankid-confirm" name="action" value="confirm">Potvrdit / Confirm</button>
              <button type="submit" id="bankid-cancel" name="action" value="cancel" class="secondary">Zrušit / Cancel</button>
            </form>
            <p class="hint">Sandbox Bank iD — žádné skutečné přihlášení / <span lang="en">no real sign-in</span>.</p>
            """,
            "assets/bankid.css",
        )
    }

    /** [stylesheet] is relative to the page's own path (the confirm page sits one level deeper). */
    fun error(reason: String, stylesheet: String = "assets/bankid.css"): String = page(
        "Bank iD — chyba",
        """
        <h1>Bank iD</h1>
        <p class="err" id="bankid-error" role="alert">Přihlášení nelze dokončit.<br>
        <span lang="en">This sign-in cannot be completed.</span></p>
        <p class="hint">${escape(reason)}</p>
        """,
        stylesheet,
    )

    const val STYLESHEET = """
body{font-family:-apple-system,system-ui,sans-serif;margin:0;padding:24px;background:#f2f4f8;color:#111}
main{max-width:440px;margin:0 auto;background:#fff;border-radius:16px;padding:24px;box-shadow:0 2px 12px #0001}
h1{font-size:22px;margin:0 0 4px;color:#00385d}.sub{color:#556;font-size:13px;margin:0 0 16px}
fieldset{border:1px solid #ccd;border-radius:12px;padding:8px 12px;margin:16px 0}legend{font-size:13px;color:#445}
.bank{display:block;padding:10px 0;font-size:17px}.who{font-size:15px}
button{display:block;width:100%;margin-top:14px;padding:14px;border:0;border-radius:12px;background:#00385d;
color:#fff;font-size:17px}button.secondary{background:#e6e9ef;color:#223}.hint{font-size:12px;color:#889}
.err{color:#b3261e;font-size:18px}
"""

    private fun page(title: String, body: String, stylesheet: String): String =
        """<!doctype html><html lang="cs"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1"><title>${escape(title)}</title>
        <link rel="stylesheet" href="$stylesheet"></head>
        <body><main>$body</main></body></html>"""

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
