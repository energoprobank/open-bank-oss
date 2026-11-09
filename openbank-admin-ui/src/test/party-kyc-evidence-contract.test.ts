// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { parseKycCaseEvidence, parseKycCasePageEvidence } from '@/lib/parties/kycEvidenceContract'

const evidence = {
  id: 'case-1', partyId: 'party-1', status: 'UNDER_REVIEW', riskLevel: 'HIGH',
  checks: [{ id: 'check-1', caseId: 'case-1', checkType: 'IDENTITY', status: 'PASSED', result: 'verified' }],
  reviewedBy: 'operator-2', createdAt: '2026-09-10T05:00:00Z', updatedAt: '2026-09-10T06:00:00Z',
}

describe('party KYC evidence contract', () => {
  it('accepts the runtime list-of-checks shape', () => {
    expect(parseKycCaseEvidence(evidence, 'party-1')).toMatchObject({
      id: 'case-1', partyId: 'party-1', checks: [{ id: 'check-1', checkType: 'IDENTITY', status: 'PASSED' }],
    })
  })

  it('rejects evidence belonging to another party', () => {
    expect(parseKycCaseEvidence(evidence, 'party-2')).toBeNull()
  })

  it('rejects the stale map-shaped checks contract and malformed states', () => {
    expect(parseKycCaseEvidence({ ...evidence, checks: { IDENTITY: 'PASSED' } }, 'party-1')).toBeNull()
    expect(parseKycCaseEvidence({ ...evidence, riskLevel: 'UNBOUNDED' }, 'party-1')).toBeNull()
    expect(parseKycCaseEvidence({ ...evidence, checks: [{ ...evidence.checks[0], status: 'UNKNOWN' }] }, 'party-1')).toBeNull()
  })

  it('validates every case and the exact requested collection window', () => {
    const page = { items: [evidence], total: 1, page: 0, size: 20, statusFilter: null }
    expect(parseKycCasePageEvidence(page, 0, 20)?.items).toHaveLength(1)
    expect(parseKycCasePageEvidence({ ...page, page: 1 }, 0, 20)).toBeNull()
    expect(parseKycCasePageEvidence({ ...page, items: [{ ...evidence, checks: {} }] }, 0, 20)).toBeNull()
  })
})

describe('KYC case contract — onboarding assessment fields (experience v2 step 9)', () => {
  it('leaves every new field absent on an old case', () => {
    const parsed = parseKycCaseEvidence(evidence, 'party-1')
    expect(parsed).not.toBeNull()
    expect(parsed?.evidence).toBeUndefined()
    expect(parsed?.dueDiligence).toBeUndefined()
    expect(parsed?.policyVersion).toBeUndefined()
    expect(parsed?.reviewReasons).toBeUndefined()
    expect(parsed?.decidedBy).toBeUndefined()
  })

  it('treats explicit nulls as absent', () => {
    const parsed = parseKycCaseEvidence({ ...evidence, evidence: null, dueDiligence: null, policyVersion: null, reviewReasons: null, decidedBy: null }, 'party-1')
    expect(parsed).not.toBeNull()
    expect(parsed?.evidence).toBeUndefined()
  })

  it('parses the full assessment shape', () => {
    const parsed = parseKycCaseEvidence({
      ...evidence,
      dueDiligence: 'EDD', policyVersion: 'onboarding-policy-v1', reviewReasons: ['FACE_MATCH_GREY'], decidedBy: 'onboarding-service',
      evidence: {
        method: 'DOCUMENT',
        identityChecks: [{ type: 'MRZ', status: 'PASSED', detail: 'ok' }],
        face: { liveness: 0.97, match: 0.72 },
        risk: { score: 10, rating: 'LOW', factors: [{ code: 'PEP', points: 10, label: 'PEP relative' }] },
        firstPayment: { payerName: 'Jan', amount: 1, currency: 'CZK', nameMatch: true },
        pid: { decision: 'NO_MATCH' },
      },
    }, 'party-1')
    expect(parsed).toMatchObject({
      dueDiligence: 'EDD', policyVersion: 'onboarding-policy-v1', reviewReasons: ['FACE_MATCH_GREY'], decidedBy: 'onboarding-service',
      evidence: {
        method: 'DOCUMENT', identityChecks: [{ type: 'MRZ', status: 'PASSED', detail: 'ok' }],
        face: { liveness: 0.97, match: 0.72 }, risk: { score: 10, factors: [{ code: 'PEP', points: 10 }] },
        firstPayment: { amount: '1', currency: 'CZK', nameMatch: true }, pid: { decision: 'NO_MATCH' }, unrecognised: {},
      },
    })
  })

  it('never rejects a case for malformed or unknown evidence — it falls back to raw', () => {
    const parsed = parseKycCaseEvidence({
      ...evidence,
      reviewReasons: ['OK_REASON', 7],
      dueDiligence: 42,
      evidence: { identityChecks: [{ type: 'MRZ' }], futureSection: { x: 1 }, method: 'BANK_ID' },
    }, 'party-1')
    expect(parsed).not.toBeNull()
    expect(parsed?.reviewReasons).toEqual(['OK_REASON'])
    expect(parsed?.dueDiligence).toBeUndefined()
    expect(parsed?.evidence?.method).toBe('BANK_ID')
    expect(parsed?.evidence?.identityChecks).toBeUndefined()
    expect(parsed?.evidence?.unrecognised).toEqual({ identityChecks: [{ type: 'MRZ' }], futureSection: { x: 1 } })
  })
})
