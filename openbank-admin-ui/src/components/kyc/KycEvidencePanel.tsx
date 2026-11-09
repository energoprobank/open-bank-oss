// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import type { ReactNode } from 'react'
import Link from 'next/link'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { ShortId, StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { isEmptyKycEvidence, type KycCaseEvidence } from '@/lib/parties/kycEvidenceContract'

/**
 * Face-biometrics thresholds the onboarding policy applies (experience v2 step 9). A score at or
 * above PASS is a match; between REVIEW and PASS is the grey zone that sends the case to a human;
 * below REVIEW fails. Shown to the reviewer so a 0.72 reads as "grey zone", not as a number.
 */
export const FACE_REVIEW_THRESHOLD = 0.6
export const FACE_PASS_THRESHOLD = 0.8

export function faceTone(score: number): Tone {
  if (score >= FACE_PASS_THRESHOLD) return 'success'
  if (score >= FACE_REVIEW_THRESHOLD) return 'warning'
  return 'danger'
}

const STATUS_TONE: Record<string, Tone> = {
  PASSED: 'success', VALID: 'success', CLEAR: 'success', WHITELISTED: 'success', NO_RECORD: 'success',
  MATCH: 'success', NO_MATCH: 'success', LOW: 'success', CDD: 'info',
  REVIEW: 'warning', POTENTIAL_HIT: 'warning', MEDIUM: 'warning', NEEDS_MANUAL_VERIFICATION: 'warning',
  MATCH_EXISTING: 'warning', EDD: 'warning',
  FAILED: 'danger', INVALID: 'danger', HIT: 'danger', RECORD: 'danger', MISMATCH: 'danger',
  HIGH: 'danger', VERY_HIGH: 'danger',
}
const tone = (value: string | undefined): Tone => (value ? STATUS_TONE[value] ?? 'neutral' : 'neutral')
const human = (value: string) => value.replace(/_/g, ' ')

function Section({ title, children, testId }: { title: string; children: ReactNode; testId: string }) {
  return (
    <section data-testid={testId} style={{ borderTop: '1px solid var(--border)', paddingTop: 10, marginTop: 10 }}>
      <h4 style={{ margin: '0 0 6px', fontSize: 12, fontWeight: 650, color: 'var(--text-secondary)' }}>{title}</h4>
      {children}
    </section>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, fontSize: 12.5, padding: '3px 0' }}>
      <span style={{ color: 'var(--text-muted)' }}>{label}</span>
      <span style={{ fontWeight: 500, textAlign: 'right', wordBreak: 'break-word' }}>{children}</span>
    </div>
  )
}

const tableStyle = { width: '100%', fontSize: 12 } as const

/**
 * Renders the structured onboarding evidence a KYC case was assessed on, plus the case-level
 * assessment facts (due diligence, review reasons, policy version, decider). Every section is
 * optional; anything the console cannot interpret is offered as raw JSON rather than dropped.
 */
export function KycEvidencePanel({ kycCase }: { kycCase: KycCaseEvidence }) {
  const { t } = useLanguage()
  const evidence = kycCase.evidence
  const yesNo = (value: boolean | undefined) =>
    value === undefined ? '—' : value
      ? <StatusBadge status="MATCH" tone="success" label={t('ano', 'yes')} />
      : <StatusBadge status="MISMATCH" tone="danger" label={t('ne', 'no')} />
  const hasCaseFacts = Boolean(kycCase.dueDiligence || kycCase.policyVersion || kycCase.decidedBy || kycCase.reviewReasons?.length)

  if (isEmptyKycEvidence(evidence) && !hasCaseFacts) {
    return (
      <div data-testid="kyc-evidence-empty" style={{ fontSize: 12, color: 'var(--text-muted)', padding: '8px 0' }}>
        {t('Případ nenese žádné strukturované důkazy z onboardingu.', 'This case carries no structured onboarding evidence.')}
      </div>
    )
  }

  const matchLabels: Record<string, string> = {
    name: t('Jméno', 'Name'), birthdate: t('Datum narození', 'Birthdate'),
    nationalId: t('Rodné číslo', 'National ID'), sex: t('Pohlaví', 'Sex'),
  }

  return (
    <div data-testid="kyc-evidence-panel" style={{ fontSize: 12.5 }}>
      {kycCase.reviewReasons && kycCase.reviewReasons.length > 0 && (
        <div data-testid="kyc-review-reasons" style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6 }}>
          <span style={{ fontSize: 12, color: 'var(--text-muted)', marginRight: 4 }}>{t('Důvody ruční kontroly:', 'Review reasons:')}</span>
          {kycCase.reviewReasons.map(reason => (
            <StatusBadge key={reason} status={reason} tone="warning" label={human(reason)} />
          ))}
        </div>
      )}

      {evidence?.method && <Row label={t('Metoda ověření', 'Verification method')}>{human(evidence.method)}</Row>}

      {evidence?.identityChecks && evidence.identityChecks.length > 0 && (
        <Section testId="kyc-evidence-identity-checks" title={t('Kontroly identity', 'Identity checks')}>
          <table className="data-table" style={tableStyle}>
            <thead><tr><th>{t('Kontrola', 'Check')}</th><th>{t('Stav', 'Status')}</th><th>{t('Detail', 'Detail')}</th></tr></thead>
            <tbody>
              {evidence.identityChecks.map((check, index) => (
                <tr key={`${check.type}-${index}`}>
                  <td>{human(check.type)}</td>
                  <td><StatusBadge status={check.status} tone={tone(check.status)} /></td>
                  <td style={{ color: 'var(--text-secondary)' }}>{check.detail ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}

      {evidence?.document && (
        <Section testId="kyc-evidence-document" title={t('Doklad', 'Document')}>
          <Row label={t('Typ', 'Type')}>{evidence.document.type ? human(evidence.document.type) : '—'}</Row>
          <Row label={t('Vydávající země', 'Issuing country')}>{evidence.document.issuingCountry ?? '—'}</Row>
          <Row label={t('Číslo dokladu', 'Document number')}>{evidence.document.numberLast4 ? `****${evidence.document.numberLast4}` : '—'}</Row>
          <Row label={t('Platnost do', 'Expiry')}>{evidence.document.expiry ?? '—'}</Row>
          <Row label={t('Veden v registru neplatných dokladů', 'Listed in invalid-document register')}>
            {evidence.document.registerListed === undefined ? '—' : evidence.document.registerListed
              ? <StatusBadge status="RECORD" tone="danger" label={t('ano', 'yes')} />
              : <StatusBadge status="NO_RECORD" tone="success" label={t('ne', 'no')} />}
          </Row>
        </Section>
      )}

      {evidence?.chip && (
        <Section testId="kyc-evidence-chip" title={t('Čip dokladu', 'Document chip')}>
          <Row label={t('Pasivní autentizace', 'Passive authentication')}>
            {evidence.chip.passiveAuthentication
              ? <StatusBadge status={evidence.chip.passiveAuthentication} tone={tone(evidence.chip.passiveAuthentication)} />
              : '—'}
          </Row>
          {evidence.chip.signer && <Row label={t('Podepisující certifikát', 'Document signer')}>{evidence.chip.signer}</Row>}
        </Section>
      )}

      {evidence?.face && (
        <Section testId="kyc-evidence-face" title={t('Obličej', 'Face')}>
          {([['liveness', t('Živost (liveness)', 'Liveness')], ['match', t('Shoda s dokladem', 'Match to document')]] as const).map(([key, label]) => {
            const score = evidence.face?.[key]
            return (
              <Row key={key} label={label}>
                {score === undefined ? '—' : <StatusBadge status={key} tone={faceTone(score)} label={score.toFixed(2)} />}
              </Row>
            )
          })}
          <p data-testid="kyc-face-thresholds" style={{ margin: '4px 0 0', fontSize: 11.5, color: 'var(--text-muted)' }}>
            {t(
              `Prahy: ≥ ${FACE_PASS_THRESHOLD.toFixed(2)} vyhovuje; ${FACE_REVIEW_THRESHOLD.toFixed(2)}–${FACE_PASS_THRESHOLD.toFixed(2)} šedá zóna → ruční kontrola; < ${FACE_REVIEW_THRESHOLD.toFixed(2)} nevyhovuje.`,
              `Thresholds: ≥ ${FACE_PASS_THRESHOLD.toFixed(2)} passes; ${FACE_REVIEW_THRESHOLD.toFixed(2)}–${FACE_PASS_THRESHOLD.toFixed(2)} grey zone → manual review; < ${FACE_REVIEW_THRESHOLD.toFixed(2)} fails.`,
            )}
          </p>
        </Section>
      )}

      {evidence?.bankId && (
        <Section testId="kyc-evidence-bankid" title={t('Bankovní identita (Bank iD)', 'Bank iD')}>
          <Row label={t('Jméno', 'Given name')}>{evidence.bankId.claims.givenName ?? '—'}</Row>
          <Row label={t('Příjmení', 'Family name')}>{evidence.bankId.claims.familyName ?? '—'}</Row>
          <Row label={t('Datum narození', 'Birthdate')}>{evidence.bankId.claims.birthdate ?? '—'}</Row>
          <Row label={t('Pohlaví', 'Gender')}>{evidence.bankId.claims.gender ?? '—'}</Row>
          {Object.keys(evidence.bankId.matches).length > 0 && (
            <div style={{ marginTop: 6 }}>
              <div style={{ fontSize: 11.5, color: 'var(--text-muted)', marginBottom: 2 }}>{t('Shoda s žadatelem', 'Matches the applicant')}</div>
              {Object.entries(evidence.bankId.matches).map(([key, match]) => (
                <Row key={key} label={matchLabels[key] ?? key}>{yesNo(match)}</Row>
              ))}
            </div>
          )}
        </Section>
      )}

      {evidence?.registers && evidence.registers.length > 0 && (
        <Section testId="kyc-evidence-registers" title={t('Registry', 'Registers')}>
          <table className="data-table" style={tableStyle}>
            <thead><tr><th>{t('Registr', 'Register')}</th><th>{t('Výsledek', 'Result')}</th><th>{t('Detail', 'Detail')}</th></tr></thead>
            <tbody>
              {evidence.registers.map((register, index) => (
                <tr key={`${register.name}-${index}`}>
                  <td>{human(register.name)}</td>
                  <td><StatusBadge status={register.result} tone={tone(register.result)} label={human(register.result)} /></td>
                  <td style={{ color: 'var(--text-secondary)' }}>{register.detail ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}

      {evidence?.screening && evidence.screening.length > 0 && (
        <Section testId="kyc-evidence-screening" title={t('Sankce a PEP', 'Sanctions and PEP screening')}>
          <table className="data-table" style={tableStyle}>
            <thead><tr><th>{t('Stav', 'Status')}</th><th>{t('Nejvyšší skóre', 'Top score')}</th><th>{t('Seznamy', 'Lists')}</th><th>{t('Prověrka', 'Check')}</th></tr></thead>
            <tbody>
              {evidence.screening.map((row, index) => (
                <tr key={`${row.checkId ?? 'screening'}-${index}`}>
                  <td><StatusBadge status={row.status} tone={tone(row.status)} label={human(row.status)} /></td>
                  <td>{row.topScore === undefined ? '—' : row.topScore.toFixed(2)}</td>
                  <td style={{ color: 'var(--text-secondary)' }}>{row.lists ?? '—'}</td>
                  <td>
                    {row.checkId
                      ? <Link href="/sanctions" data-testid="kyc-screening-link" style={{ color: 'var(--accent)', textDecoration: 'none' }}>
                          <ShortId value={row.checkId} copyLabel={t('Kopírovat ID prověrky', 'Copy check ID')} /> →
                        </Link>
                      : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
      )}

      {(evidence?.risk || kycCase.dueDiligence) && (
        <Section testId="kyc-evidence-risk" title={t('Riziko', 'Risk')}>
          {evidence?.risk?.score !== undefined && <Row label={t('Skóre', 'Score')}>{evidence.risk.score}</Row>}
          <Row label={t('Hodnocení', 'Rating')}>
            {evidence?.risk?.rating
              ? <StatusBadge status={evidence.risk.rating} tone={tone(evidence.risk.rating)} label={human(evidence.risk.rating)} />
              : <StatusBadge status={kycCase.riskLevel} tone={tone(kycCase.riskLevel)} label={human(kycCase.riskLevel)} />}
          </Row>
          {kycCase.dueDiligence && (
            <Row label={t('Hloubka péče', 'Due diligence')}>
              <StatusBadge status={kycCase.dueDiligence} tone={tone(kycCase.dueDiligence)} />
            </Row>
          )}
          {evidence?.risk && evidence.risk.factors.length > 0 && (
            <table className="data-table" style={{ ...tableStyle, marginTop: 6 }}>
              <thead><tr><th>{t('Faktor', 'Factor')}</th><th>{t('Popis', 'Label')}</th><th>{t('Body', 'Points')}</th></tr></thead>
              <tbody>
                {evidence.risk.factors.map((factor, index) => (
                  <tr key={`${factor.code}-${index}`}>
                    <td>{human(factor.code)}</td>
                    <td style={{ color: 'var(--text-secondary)' }}>{factor.label ?? '—'}</td>
                    <td>{factor.points ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Section>
      )}

      {evidence?.firstPayment && (
        <Section testId="kyc-evidence-first-payment" title={t('První platba', 'First payment')}>
          <Row label={t('Plátce', 'Payer')}>{evidence.firstPayment.payerName ?? '—'}</Row>
          <Row label={t('Účet plátce', 'Payer account')}>{evidence.firstPayment.payerAccountMasked ?? '—'}</Row>
          <Row label={t('Banka', 'Bank')}>{evidence.firstPayment.payerBank ?? '—'}</Row>
          <Row label={t('Částka', 'Amount')}>
            {evidence.firstPayment.amount ? `${evidence.firstPayment.amount} ${evidence.firstPayment.currency ?? ''}`.trim() : '—'}
          </Row>
          <Row label={t('Shoda jména', 'Name match')}>{yesNo(evidence.firstPayment.nameMatch)}</Row>
          {evidence.firstPayment.receivedAt && <Row label={t('Přijato', 'Received')}>{evidence.firstPayment.receivedAt}</Row>}
        </Section>
      )}

      {evidence?.pid && (
        <Section testId="kyc-evidence-pid" title={t('Jednoznačnost osoby (PID)', 'Person identity (PID)')}>
          <Row label={t('Rozhodnutí', 'Decision')}>
            <StatusBadge status={evidence.pid.decision} tone={tone(evidence.pid.decision)} label={human(evidence.pid.decision)} />
          </Row>
          {evidence.pid.caseId && (
            <Row label={t('Případ ověření identity', 'Identity case')}>
              <Link href="/identity-cases" data-testid="kyc-pid-link" style={{ color: 'var(--accent)', textDecoration: 'none' }}>
                <ShortId value={evidence.pid.caseId} copyLabel={t('Kopírovat ID případu', 'Copy case ID')} /> →
              </Link>
            </Row>
          )}
        </Section>
      )}

      {(kycCase.policyVersion || kycCase.decidedBy) && (
        <Section testId="kyc-evidence-decision" title={t('Rozhodnutí', 'Decision')}>
          {kycCase.policyVersion && <Row label={t('Verze politiky', 'Policy version')}>{kycCase.policyVersion}</Row>}
          {kycCase.decidedBy && <Row label={t('Rozhodl', 'Decided by')}>{kycCase.decidedBy}</Row>}
        </Section>
      )}

      {evidence && Object.keys(evidence.unrecognised).length > 0 && (
        <details data-testid="kyc-evidence-raw" style={{ borderTop: '1px solid var(--border)', paddingTop: 10, marginTop: 10 }}>
          <summary style={{ cursor: 'pointer', fontSize: 12, color: 'var(--text-secondary)' }}>
            {t('Další důkazy (nezpracovaný JSON)', 'Other evidence (raw JSON)')}
          </summary>
          <pre className="mono" style={{ margin: '6px 0 0', fontSize: 11, whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: 'var(--surface-2)', padding: 8, borderRadius: 6 }}>
            {JSON.stringify(evidence.unrecognised, null, 2)}
          </pre>
        </details>
      )}
    </div>
  )
}
