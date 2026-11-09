// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parseKycCaseEvidence, type KycCaseEvidence } from '@/lib/parties/kycEvidenceContract'

const sessionState: { roles: string[] } = { roles: ['ROLE_KYC_REVIEWER'] }
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { id: 'sub-1', name: 'Reviewer', roles: sessionState.roles } }, status: 'authenticated' }),
}))

import { KycEvidencePanel } from '@/components/kyc/KycEvidencePanel'
import { KycDecisionActions, isValidKycReason } from '@/components/kyc/KycDecisionActions'

const CASE_ID = '5b0c3c6e-2d47-4f3c-9a51-0c1f3a3c9e11'
const base = {
  id: CASE_ID, partyId: 'party-1', status: 'UNDER_REVIEW', riskLevel: 'MEDIUM',
  checks: [{ id: 'c-1', checkType: 'IDENTITY', status: 'MANUAL_REVIEW', result: null }],
  createdAt: '2026-11-01T10:00:00Z', updatedAt: '2026-11-01T10:05:00Z',
}
const FULL = {
  ...base,
  dueDiligence: 'EDD', policyVersion: 'onboarding-policy-v1', reviewReasons: ['FACE_MATCH_GREY', 'FIRST_PAYMENT_NAME_MISMATCH'],
  decidedBy: 'onboarding-service',
  evidence: {
    method: 'DOCUMENT',
    identityChecks: [{ type: 'FACE_MATCH', status: 'REVIEW', detail: 'score in grey zone' }, { type: 'MRZ', status: 'PASSED' }],
    document: { type: 'ID_CARD', issuingCountry: 'CZE', numberLast4: '4321', expiry: '2031-05-01', registerListed: false },
    chip: { passiveAuthentication: 'VALID', signer: 'CZ DS 07' },
    face: { liveness: 0.97, match: 0.72 },
    bankId: { claims: { givenName: 'Jana', familyName: 'Nováková', birthdate: '1991-08-13', gender: 'female' }, matches: { name: true, birthdate: true, nationalId: false, sex: true } },
    registers: [{ name: 'ISIR', result: 'NO_RECORD' }, { name: 'MVCR_INVALID_DOCUMENTS', result: 'NO_RECORD', detail: 'checked' }],
    screening: [{ checkId: '7e2a1b44-1111-4c22-8d33-aaaaaaaaaaaa', status: 'POTENTIAL_HIT', topScore: 0.81, lists: 'EU, UN' }],
    risk: { score: 35, rating: 'MEDIUM', factors: [{ code: 'HIGH_RISK_COUNTRY', points: 25, label: 'Nationality high-risk' }] },
    firstPayment: { payerName: 'Jana Novakova', payerAccountMasked: 'CZ65 **** 1234', payerBank: 'KB', amount: '1.00', currency: 'CZK', nameMatch: false },
    pid: { decision: 'NEEDS_MANUAL_VERIFICATION', caseId: '9f8e7d6c-5b4a-4321-8765-0fedcba98765' },
    somethingNew: { foo: 'bar' },
  },
}

const parse = (value: unknown) => parseKycCaseEvidence(value) as KycCaseEvidence

function mount(node: React.ReactNode) {
  return render(<LanguageProvider>{node}</LanguageProvider>)
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('openbank-admin-lang', 'en')
  sessionState.roles = ['ROLE_KYC_REVIEWER']
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('KycEvidencePanel', () => {
  it('renders every evidence section of a full assessment', async () => {
    await act(async () => { mount(<KycEvidencePanel kycCase={parse(FULL)} />) })
    for (const id of ['identity-checks', 'document', 'chip', 'face', 'bankid', 'registers', 'screening', 'risk', 'first-payment', 'pid', 'decision']) {
      expect(screen.getByTestId(`kyc-evidence-${id}`)).toBeInTheDocument()
    }
    expect(screen.getByTestId('kyc-review-reasons')).toHaveTextContent('FACE MATCH GREY')
    expect(screen.getByTestId('kyc-evidence-document')).toHaveTextContent('****4321')
    expect(screen.getByTestId('kyc-evidence-face')).toHaveTextContent('0.72')
    expect(screen.getByTestId('kyc-face-thresholds')).toHaveTextContent('0.80')
    expect(screen.getByTestId('kyc-face-thresholds')).toHaveTextContent('0.60')
    expect(screen.getByTestId('kyc-screening-link')).toHaveAttribute('href', '/sanctions')
    expect(screen.getByTestId('kyc-pid-link')).toHaveAttribute('href', '/identity-cases')
    expect(screen.getByTestId('kyc-evidence-risk')).toHaveTextContent('EDD')
    expect(screen.getByTestId('kyc-evidence-risk')).toHaveTextContent('Nationality high-risk')
    expect(screen.getByTestId('kyc-evidence-first-payment')).toHaveTextContent('CZ65 **** 1234')
    expect(screen.getByTestId('kyc-evidence-decision')).toHaveTextContent('onboarding-policy-v1')
    expect(screen.getByTestId('kyc-evidence-decision')).toHaveTextContent('onboarding-service')
    expect(screen.getByTestId('kyc-evidence-raw')).toHaveTextContent('somethingNew')
  })

  it('renders a calm empty state for a case with no evidence', async () => {
    await act(async () => { mount(<KycEvidencePanel kycCase={parse(base)} />) })
    expect(screen.getByTestId('kyc-evidence-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('kyc-evidence-panel')).toBeNull()
  })

  it('renders an empty evidence object with no sections', async () => {
    await act(async () => { mount(<KycEvidencePanel kycCase={parse({ ...base, evidence: {} })} />) })
    expect(screen.getByTestId('kyc-evidence-empty')).toBeInTheDocument()
  })
})

describe('KycDecisionActions', () => {
  it('validates the reason length', () => {
    expect(isValidKycReason('too short')).toBe(false)
    expect(isValidKycReason('          x')).toBe(false)
    expect(isValidKycReason('documents verified')).toBe(true)
  })

  it.each(['OPEN', 'APPROVED', 'REJECTED', 'DOCUMENTS_REQUIRED', 'EXPIRED'])('shows no actions for a %s case', async status => {
    await act(async () => { mount(<KycDecisionActions kycCase={parse({ ...base, status })} onDecided={() => {}} />) })
    expect(screen.queryByTestId('kyc-decision-actions')).toBeNull()
    expect(screen.queryByRole('button', { name: /approve|reject/i })).toBeNull()
  })

  it('shows approve and reject for an UNDER_REVIEW case to a KYC reviewer', async () => {
    await act(async () => { mount(<KycDecisionActions kycCase={parse(base)} onDecided={() => {}} />) })
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })

  it('hides the actions from an operator without kyc:approve', async () => {
    sessionState.roles = ['ROLE_KYC_OPENER']
    await act(async () => { mount(<KycDecisionActions kycCase={parse(base)} onDecided={() => {}} />) })
    expect(screen.queryByTestId('kyc-decision-actions')).toBeNull()
    expect(screen.getByTestId('kyc-decision-forbidden')).toBeInTheDocument()
  })

  it('requires a 10-character reason, posts it via the BFF and refreshes', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ ...base, status: 'APPROVED' }), { status: 200, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const onDecided = vi.fn()
    await act(async () => { mount(<KycDecisionActions kycCase={parse(base)} onDecided={onDecided} />) })
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByTestId('kyc-decision-dialog')
    const confirm = within(dialog).getByRole('button', { name: 'Confirm approval' })
    const textarea = within(dialog).getByLabelText('Reason (required)')
    expect(confirm).toBeDisabled()
    fireEvent.change(textarea, { target: { value: 'short' } })
    expect(within(dialog).getByTestId('kyc-reason-counter')).toHaveTextContent('5 / min. 10')
    expect(confirm).toBeDisabled()
    fireEvent.change(textarea, { target: { value: 'Documents verified manually' } })
    expect(confirm).toBeEnabled()
    await act(async () => { fireEvent.click(confirm) })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe(`/api/svc/kyc-service/api/v1/kyc/cases/${CASE_ID}/approve`)
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ reason: 'Documents verified manually' })
    expect(onDecided).toHaveBeenCalledTimes(1)
    expect(screen.queryByTestId('kyc-decision-dialog')).toBeNull()
  })

  it('shows the service message of a 409 (same actor as the assessment) inline', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ message: 'Reviewer must differ from the assessment actor' }), { status: 409, headers: { 'content-type': 'application/json' } })))
    const onDecided = vi.fn()
    await act(async () => { mount(<KycDecisionActions kycCase={parse(base)} onDecided={onDecided} />) })
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByTestId('kyc-decision-dialog')
    fireEvent.change(within(dialog).getByLabelText('Reason (required)'), { target: { value: 'Identity not proven' } })
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm rejection' })) })
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Reviewer must differ from the assessment actor')
    expect(onDecided).not.toHaveBeenCalled()
  })

  it('shows a 403 as a permission message, never a raw status', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 403 })))
    await act(async () => { mount(<KycDecisionActions kycCase={parse(base)} onDecided={() => {}} />) })
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByTestId('kyc-decision-dialog')
    fireEvent.change(within(dialog).getByLabelText('Reason (required)'), { target: { value: 'Documents verified manually' } })
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm approval' })) })
    expect(within(dialog).getByRole('alert')).toHaveTextContent('not permitted')
    expect(within(dialog).getByRole('alert')).not.toHaveTextContent('403')
  })
})
