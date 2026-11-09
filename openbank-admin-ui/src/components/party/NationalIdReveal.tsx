// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useRef, useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { Can } from '@/components/auth/AuthGuard'

/** How long a revealed national id stays on screen before it is hidden again. */
export const NATIONAL_ID_REVEAL_MS = 30_000

/**
 * The masked tax id exactly as party-service returns it, plus — for a KYC reviewer / compliance
 * officer (`kyc:approve`, a subset of the endpoint's own @RolesAllowed) — a "Reveal" control that
 * fetches the clear national id through the BFF.
 *
 * The clear number is PII: it lives only in this component's state, is fetched with
 * `cache: 'no-store'` on a path that carries nothing but the party id, is never logged, and is
 * dropped after [NATIONAL_ID_REVEAL_MS], on "Hide", or on unmount. party-service audits every call.
 * Mount it with `key={partyId}` so a different party never inherits a revealed number.
 */
export function NationalIdReveal({ partyId, maskedTaxId }: { partyId: string; maskedTaxId: string | undefined }) {
  const { t } = useLanguage()
  const [clear, setClear] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const hide = () => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
    setClear(null)
  }

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])

  const reveal = async () => {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      const res = await fetch(svcUrl('party-service', `/api/v1/parties/${encodeURIComponent(partyId)}/identity/national-id`), {
        cache: 'no-store',
        signal: AbortSignal.timeout(8000),
      })
      if (!res.ok) {
        setError(res.status === 401 || res.status === 403
          ? t('Nemáte oprávnění zobrazit rodné číslo.', 'You are not permitted to reveal the national ID.')
          : res.status === 404
            ? t('Rodné číslo není k dispozici.', 'No national ID is on record.')
            : t('Rodné číslo se nepodařilo načíst.', 'The national ID could not be loaded.'))
        return
      }
      const body = await res.json().catch(() => null) as { partyId?: unknown; nationalId?: unknown } | null
      if (!body || body.partyId !== partyId || typeof body.nationalId !== 'string' || !body.nationalId.trim()) {
        setError(t('Rodné číslo se nepodařilo načíst.', 'The national ID could not be loaded.'))
        return
      }
      setClear(body.nationalId)
      if (timer.current) clearTimeout(timer.current)
      timer.current = setTimeout(() => { timer.current = null; setClear(null) }, NATIONAL_ID_REVEAL_MS)
    } catch {
      setError(t('Služba klientů neodpověděla.', 'The party service did not answer.'))
    } finally {
      setBusy(false)
    }
  }

  const buttonStyle = { fontSize: '11px', padding: '2px 8px', marginLeft: '6px' } as const

  return (
    <span data-testid="national-id-reveal" style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-end' }}>
      <span>
        <span data-testid="national-id-value" className={clear ? 'mono' : undefined}>{clear ?? maskedTaxId ?? '—'}</span>
        {maskedTaxId && (
          <Can permission="kyc:approve">
            {clear
              ? <button type="button" className="btn btn-secondary" style={buttonStyle} onClick={hide}>
                  <EyeOff size={11} aria-hidden="true" style={{ marginRight: 3 }} />{t('Skrýt', 'Hide')}
                </button>
              : <button type="button" className="btn btn-secondary" style={buttonStyle} disabled={busy} aria-busy={busy} onClick={() => void reveal()}>
                  <Eye size={11} aria-hidden="true" style={{ marginRight: 3 }} />{t('Zobrazit', 'Reveal')}
                </button>}
          </Can>
        )}
      </span>
      {clear && (
        <span data-testid="national-id-logged" style={{ fontSize: '11px', color: 'var(--text-muted)', fontWeight: 400 }}>
          {t('Přístup je zaznamenán v auditu.', 'Access is logged.')}
        </span>
      )}
      {error && <span role="alert" style={{ fontSize: '11px', color: 'var(--danger)', fontWeight: 400 }}>{error}</span>}
    </span>
  )
}
