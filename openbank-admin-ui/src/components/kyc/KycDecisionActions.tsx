// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useRef, useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { Check, ShieldCheck, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import { Can } from '@/components/auth/AuthGuard'
import { ShortId } from '@/components/ui'
import type { KycCaseEvidence } from '@/lib/parties/kycEvidenceContract'

/** kyc-service `KycService.approveCase/rejectCase` refuses a shorter reason (ČNB audit trail). */
export const KYC_REASON_MIN_LENGTH = 10

type Decision = 'approve' | 'reject'

export function isDecidableKycCase(kycCase: Pick<KycCaseEvidence, 'status'>): boolean {
  return kycCase.status === 'UNDER_REVIEW'
}

export function isValidKycReason(reason: string): boolean {
  return reason.trim().length >= KYC_REASON_MIN_LENGTH
}

/**
 * Approve / Reject for a KYC case awaiting manual review. Rendered only for an UNDER_REVIEW case
 * and only to an operator holding `kyc:approve`. The reviewer's identity is NOT sent: kyc-service
 * takes it from the bearer token the BFF relays, and refuses (409) a reviewer who is also the
 * assessment actor — that message is shown to the operator as-is.
 */
export function KycDecisionActions({ kycCase, onDecided }: { kycCase: KycCaseEvidence; onDecided: () => void }) {
  const { t } = useLanguage()
  const [decision, setDecision] = useState<Decision | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)

  if (!isDecidableKycCase(kycCase)) return null

  const open = (next: Decision, trigger: HTMLButtonElement) => {
    triggerRef.current = trigger
    setDecision(next)
  }
  const close = () => {
    setDecision(null)
    requestAnimationFrame(() => triggerRef.current?.focus())
  }

  return (
    <Can permission="kyc:approve" fallback={
      <div data-testid="kyc-decision-forbidden" style={{ marginTop: 10, fontSize: 12, color: 'var(--text-secondary)' }}>
        {t('Případ čeká na ruční kontrolu. Rozhodnout může pouze oprávněný KYC schvalovatel.', 'This case awaits manual review. Only an authorized KYC reviewer can decide it.')}
      </div>
    }>
      <div data-testid="kyc-decision-actions" style={{ display: 'flex', gap: 8, marginTop: 12 }}>
        <button type="button" className="btn btn-primary" style={{ fontSize: 13 }} onClick={event => open('approve', event.currentTarget)}>
          <Check size={14} aria-hidden="true" style={{ marginRight: 4 }} />{t('Schválit', 'Approve')}
        </button>
        <button type="button" className="btn btn-secondary" style={{ fontSize: 13 }} onClick={event => open('reject', event.currentTarget)}>
          <X size={14} aria-hidden="true" style={{ marginRight: 4 }} />{t('Zamítnout', 'Reject')}
        </button>
      </div>
      {decision && (
        <KycDecisionDialog
          kycCase={kycCase}
          decision={decision}
          onCancel={close}
          onDone={() => { setDecision(null); onDecided() }}
        />
      )}
    </Can>
  )
}

function KycDecisionDialog({ kycCase, decision, onCancel, onDone }: {
  kycCase: KycCaseEvidence
  decision: Decision
  onCancel: () => void
  onDone: () => void
}) {
  const { t } = useLanguage()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const flight = useSingleFlight()
  const approve = decision === 'approve'
  const valid = isValidKycReason(reason)
  const length = reason.trim().length

  const submit = async () => {
    if (!valid) return
    let succeeded = false
    const outcome = await flight.run(`kyc-decision:${kycCase.id}`, async () => {
      setBusy(true)
      setError(null)
      try {
        const res = await fetch(svcUrl('kyc-service', `/api/v1/kyc/cases/${kycCase.id}/${decision}`), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ reason: reason.trim() }),
          signal: AbortSignal.timeout(8000),
        })
        if (!res.ok) {
          const body = await res.json().catch(() => null) as { message?: unknown } | null
          const serverMessage = typeof body?.message === 'string' && body.message.trim() ? body.message : null
          if (res.status === 401 || res.status === 403) {
            setError(t('Nemáte oprávnění rozhodnout tento KYC případ.', 'You are not permitted to decide this KYC case.'))
          } else if (res.status === 409) {
            setError(serverMessage ?? t(
              'Konflikt: případ nemůže rozhodnout stejná osoba, která provedla posouzení (čtyři oči).',
              'Conflict: the case cannot be decided by the same actor that assessed it (four-eyes).',
            ))
          } else if (res.status === 422) {
            setError(serverMessage ?? t('Případ již není ve stavu ruční kontroly.', 'The case is no longer awaiting review.'))
          } else {
            setError(t('Rozhodnutí se nepodařilo uložit. Zkuste to znovu.', 'The decision could not be recorded. Try again.'))
          }
          return
        }
        succeeded = true
      } catch {
        setError(t('KYC služba neodpověděla.', 'The KYC service did not answer.'))
      } finally {
        setBusy(false)
      }
    })
    if (!wasSkipped(outcome) && succeeded) onDone()
  }

  return (
    <Dialog.Root open onOpenChange={next => { if (!next && !busy) onCancel() }}>
      <Dialog.Portal>
        <Dialog.Overlay style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.72)' }} />
        <Dialog.Content
          className="card"
          role="alertdialog"
          aria-busy={busy}
          data-testid="kyc-decision-dialog"
          onEscapeKeyDown={event => { if (busy) event.preventDefault() }}
          onPointerDownOutside={event => { if (busy) event.preventDefault() }}
          onCloseAutoFocus={event => event.preventDefault()}
          style={{ position: 'fixed', zIndex: 1201, top: '50%', left: '50%', transform: 'translate(-50%, -50%)', width: 'min(560px, calc(100% - 40px))', maxHeight: 'calc(100dvh - 40px)', overflowY: 'auto', padding: 22 }}
        >
          <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
            <ShieldCheck size={20} aria-hidden="true" style={{ color: 'var(--accent)', flexShrink: 0, marginTop: 2 }} />
            <div>
              <Dialog.Title style={{ margin: 0, fontSize: 17, fontWeight: 750 }}>
                {approve ? t('Schválit KYC případ', 'Approve KYC case') : t('Zamítnout KYC případ', 'Reject KYC case')}
              </Dialog.Title>
              <Dialog.Description style={{ margin: '6px 0 0', fontSize: 12.5, lineHeight: 1.5, color: 'var(--text-secondary)' }}>
                {approve
                  ? t('Schválení aktivuje klienta. Jako schvalovatel se zaznamená váš přihlášený účet.', 'Approval activates the party. Your signed-in account is recorded as the reviewer.')
                  : t('Zamítnutí ukončí onboarding klienta. Jako schvalovatel se zaznamená váš přihlášený účet.', 'Rejection ends the party’s onboarding. Your signed-in account is recorded as the reviewer.')}
              </Dialog.Description>
            </div>
          </div>
          <div style={{ marginTop: 12, fontSize: 12, color: 'var(--text-secondary)' }}>
            {t('Případ', 'Case')} <ShortId value={kycCase.id} copyLabel={t('Kopírovat ID případu', 'Copy case ID')} />
          </div>
          <label htmlFor="kyc-decision-reason" style={{ display: 'block', marginTop: 12, fontSize: 12, fontWeight: 600 }}>
            {t('Odůvodnění (povinné)', 'Reason (required)')}
          </label>
          <textarea
            id="kyc-decision-reason"
            className="input"
            rows={4}
            value={reason}
            disabled={busy}
            onChange={event => setReason(event.target.value)}
            aria-describedby="kyc-decision-reason-count"
            style={{ width: '100%', marginTop: 4, fontSize: 13, resize: 'vertical' }}
          />
          <div id="kyc-decision-reason-count" data-testid="kyc-reason-counter" style={{ fontSize: 11.5, marginTop: 2, color: valid ? 'var(--text-muted)' : 'var(--warning-text, var(--text-muted))' }}>
            {t(`${length} / min. ${KYC_REASON_MIN_LENGTH} znaků`, `${length} / min. ${KYC_REASON_MIN_LENGTH} characters`)}
          </div>
          {error && (
            <p role="alert" style={{ margin: '12px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12 }}>
              {error}
            </p>
          )}
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
            <button type="button" autoFocus className="btn btn-secondary" disabled={busy} onClick={onCancel}>{t('Zpět', 'Back')}</button>
            <button type="button" className="btn btn-primary" disabled={busy || !valid} aria-busy={busy} onClick={() => void submit()}>
              {busy
                ? t('Ukládám…', 'Recording…')
                : approve ? t('Potvrdit schválení', 'Confirm approval') : t('Potvrdit zamítnutí', 'Confirm rejection')}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
