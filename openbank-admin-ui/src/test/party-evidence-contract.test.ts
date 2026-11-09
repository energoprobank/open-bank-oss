// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { parsePartyEvidence } from '@/lib/parties/partyEvidenceContract'

const party = {
  id: 'party-1', partyType: 'INDIVIDUAL', status: 'ACTIVE', legalName: 'Test Customer',
  email: 'customer@example.test', kycStatus: 'APPROVED',
  address: { line1: 'Test 1', city: 'Prague', postalCode: '10000', countryCode: 'CZ' },
  createdAt: '2026-09-10T05:00:00Z', updatedAt: '2026-09-10T06:00:00Z',
}

describe('party detail evidence contract', () => {
  it('accepts complete evidence for the requested party', () => {
    expect(parsePartyEvidence(party, 'party-1')).toMatchObject({ id: 'party-1', legalName: 'Test Customer' })
  })

  it('accepts optional fields party-service sends as explicit null', () => {
    const parsed = parsePartyEvidence({ ...party, tradingName: null, phone: null, registrationNumber: null }, 'party-1')
    expect(parsed).not.toBeNull()
    expect(parsed?.phone).toBeUndefined()
  })

  it('rejects a successful response for another route identity', () => {
    expect(parsePartyEvidence(party, 'party-2')).toBeNull()
  })

  it('rejects malformed lifecycle, PII, and timestamp evidence', () => {
    expect(parsePartyEvidence({ ...party, status: 'UNKNOWN' }, 'party-1')).toBeNull()
    expect(parsePartyEvidence({ ...party, email: '' }, 'party-1')).toBeNull()
    expect(parsePartyEvidence({ ...party, updatedAt: 'later' }, 'party-1')).toBeNull()
    expect(parsePartyEvidence({ ...party, address: { city: 'Prague' } }, 'party-1')).toBeNull()
  })
})

describe('party detail evidence contract — identity fields', () => {
  it('carries the masked tax id verbatim and the new optional identity fields', () => {
    const parsed = parsePartyEvidence({
      ...party, taxId: '910813/****', nationalIdLast4: '1234', riskRating: 'LOW',
      identityVerifiedAt: '2026-11-01T10:00:00Z', identityMethod: 'BANK_ID', nationality: 'CZ', dateOfBirth: '1991-08-13',
    }, 'party-1')
    expect(parsed).toMatchObject({
      taxId: '910813/****', nationalIdLast4: '1234', riskRating: 'LOW',
      identityVerifiedAt: '2026-11-01T10:00:00Z', identityMethod: 'BANK_ID', nationality: 'CZ', dateOfBirth: '1991-08-13',
    })
  })

  it('treats absent, null or malformed new fields as not provided', () => {
    const absent = parsePartyEvidence(party, 'party-1')
    expect(absent?.riskRating).toBeUndefined()
    expect(absent?.identityMethod).toBeUndefined()
    const malformed = parsePartyEvidence({ ...party, riskRating: 3, identityVerifiedAt: null, nationalIdLast4: null }, 'party-1')
    expect(malformed).not.toBeNull()
    expect(malformed?.riskRating).toBeUndefined()
    expect(malformed?.identityVerifiedAt).toBeUndefined()
  })
})
