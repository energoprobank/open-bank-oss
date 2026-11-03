// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { affectedKycCheck, describeTopMatch, partiesInReview, partyScreening, type ScreeningCheck } from '@/lib/parties/screeningHold'

const PARTY = '4983b533-894f-4417-8847-095db3645d72'
const pep = { listType: 'PEP_GLOBAL', matchType: 'EXACT', matchScore: 1, matchedName: 'Michal Král', programs: ['PEP'] }

function check(over: Partial<ScreeningCheck>): ScreeningCheck {
  return {
    id: 'c1', idempotencyKey: `account-open:${PARTY}:onboarding-account-${PARTY}`, name: 'Michal Král',
    identifiers: { partyId: PARTY }, status: 'HIT', matches: [pep], checkedAt: '2026-11-03T10:00:00Z', ...over,
  }
}

describe('party screening hold', () => {
  it('an undecided account-opening hit is open, even with a passed KYC case', () => {
    const s = partyScreening([check({})], PARTY)
    expect(s.open?.id).toBe('c1')
    expect(s.cleared).toBeNull()
    expect(affectedKycCheck(s.open!)).toBe('PEP_SCREENING')
    expect(describeTopMatch(s.open!)).toBe('PEP_GLOBAL · EXACT · 100 % · Michal Král [PEP]')
  })

  it('a decided hit shows who cleared it; manual searches of the same name do not count', () => {
    const cleared = check({ status: 'CLEAR', reviewedBy: 'admin@openbank.local', reviewNote: 'False positive' })
    const manual = check({ id: 'm1', idempotencyKey: 'manual-1', identifiers: {}, status: 'HIT', checkedAt: '2026-11-03T11:00:00Z' })
    const s = partyScreening([cleared, manual], PARTY)
    expect(s.open).toBeNull()
    expect(s.cleared?.reviewedBy).toBe('admin@openbank.local')
  })

  it('a sanctions list match points at the sanctions check; the list view collects parties in review', () => {
    expect(affectedKycCheck(check({ matches: [{ ...pep, listType: 'OFAC_SDN', programs: [] }] }))).toBe('SANCTIONS_SCREENING')
    const legacy = check({ identifiers: {}, id: 'old' })   // created before account-service sent the partyId
    expect([...partiesInReview([legacy, check({ status: 'CLEAR', identifiers: { partyId: 'other' } })])]).toEqual([PARTY])
  })
})
