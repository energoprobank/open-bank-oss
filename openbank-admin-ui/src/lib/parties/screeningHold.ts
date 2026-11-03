// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Where a party stands with sanctions/PEP screening outside its KYC case.
//
// The KYC case (kyc-service) is decided at onboarding; account-service screens the customer AGAIN
// when it opens an account (key `account-open:<partyId>:…`, identifiers.partyId). A hit there blocks
// the account until compliance dispositions it on the Sanctions page — but the KYC case still reads
// APPROVED with its PEP and sanctions checks PASSED, so an operator looking at the party saw nothing
// to do. This reads the party's own screening checks so the party views can say "manual review".

export interface ScreeningMatch {
  listType: string
  matchType?: string
  matchScore: number
  matchedName: string
  programs?: string[]
}

export interface ScreeningCheck {
  id: string
  idempotencyKey?: string
  name: string
  identifiers?: Record<string, string>
  status: string
  matches?: ScreeningMatch[]
  overallScore?: number
  reviewedBy?: string | null
  reviewNote?: string | null
  checkedAt: string
  reviewedAt?: string | null
}

export interface PartyScreening {
  /** A hit nobody has decided yet: the account opening waits for a person. */
  open: ScreeningCheck | null
  /** The latest decided check that cleared the party (CLEAR / WHITELISTED by a person). */
  cleared: ScreeningCheck | null
}

const OPEN = new Set(['HIT', 'POTENTIAL_HIT', 'ESCALATED'])
const CLEARED = new Set(['CLEAR', 'WHITELISTED'])

/** The checks that screened this party (not manual searches of a name). */
export function partyChecks(checks: ScreeningCheck[], partyId: string): ScreeningCheck[] {
  return checks.filter(c =>
    c.identifiers?.partyId === partyId || (c.idempotencyKey ?? '').startsWith(`account-open:${partyId}:`))
}

const newestFirst = (a: ScreeningCheck, b: ScreeningCheck) => b.checkedAt.localeCompare(a.checkedAt)

export function partyScreening(checks: ScreeningCheck[], partyId: string): PartyScreening {
  const mine = partyChecks(checks, partyId).sort(newestFirst)
  const open = mine.find(c => OPEN.has(c.status)) ?? null
  // The person's decision, not a later screening that carried it over (sanctions-service notes those).
  const decided = mine.filter(c => CLEARED.has(c.status) && !!c.reviewedBy)
  const cleared = open ? null : decided.find(c => !(c.reviewNote ?? '').startsWith('Carried over')) ?? decided[0] ?? null
  return { open, cleared }
}

/** Party ids with an undecided hit, for list views (one fetch of the checks, not one per row). */
export function partiesInReview(checks: ScreeningCheck[]): Set<string> {
  const ids = new Set<string>()
  for (const c of checks) {
    if (!OPEN.has(c.status)) continue
    const fromKey = /^account-open:([0-9a-f-]{36}):/i.exec(c.idempotencyKey ?? '')?.[1]
    const id = c.identifiers?.partyId ?? fromKey
    if (id) ids.add(id)
  }
  return ids
}

/** The KYC check a match belongs to: the PEP list → PEP_SCREENING, any sanctions list → SANCTIONS_SCREENING. */
export function affectedKycCheck(check: ScreeningCheck): 'PEP_SCREENING' | 'SANCTIONS_SCREENING' {
  const top = [...(check.matches ?? [])].sort((a, b) => b.matchScore - a.matchScore)[0]
  return top?.listType?.startsWith('PEP') ? 'PEP_SCREENING' : 'SANCTIONS_SCREENING'
}

/** "PEP_GLOBAL · EXACT · 100 % · Michal Král [PEP]" — the strongest match, as the Sanctions page prints it. */
export function describeTopMatch(check: ScreeningCheck): string | null {
  const top = [...(check.matches ?? [])].sort((a, b) => b.matchScore - a.matchScore)[0]
  if (!top) return null
  const programs = top.programs?.length ? ` [${top.programs.join(', ')}]` : ''
  return `${top.listType} · ${top.matchType ?? 'MATCH'} · ${Math.round(top.matchScore * 100)} % · ${top.matchedName}${programs}`
}
