// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

const CASE_STATUSES = new Set(['OPEN', 'DOCUMENTS_REQUIRED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'EXPIRED'])
const RISK_LEVELS = new Set(['LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH'])
const CHECK_TYPES = new Set(['IDENTITY', 'ADDRESS', 'PEP_SCREENING', 'SANCTIONS_SCREENING', 'ADVERSE_MEDIA'])
const CHECK_STATUSES = new Set(['PENDING', 'PASSED', 'FAILED', 'MANUAL_REVIEW'])

export interface KycCheckEvidence {
  id: string
  checkType: string
  status: string
  result?: string | null
}

export interface KycCaseEvidence {
  id: string
  partyId: string
  status: string
  riskLevel: string
  checks: KycCheckEvidence[]
  reviewedBy?: string | null
  createdAt: string
  updatedAt: string
  // ── Onboarding assessment (experience v2 step 9) — all optional; an older case carries none ──
  /** Structured evidence the assessment was decided on. Absent on cases opened before step 9. */
  evidence?: KycEvidence
  /** `CDD` or `EDD`; any other text is shown verbatim. */
  dueDiligence?: string
  policyVersion?: string
  reviewReasons?: string[]
  /** Who (or which automated actor) decided the case. */
  decidedBy?: string
}

// ── Evidence (kyc-service section C) ───────────────────────────────────────────
// Every section is optional and parsed LENIENTLY: a section whose shape this console does not
// recognise is never a reason to hide the case — it lands in `unrecognised` and the panel shows it
// as raw JSON. The enumerations are deliberately not closed here: a new identity-check type or
// register name from kyc-service must render, not disappear.

export interface KycIdentityCheck { type: string; status: string; detail?: string }
export interface KycDocumentEvidence {
  type?: string; issuingCountry?: string; numberLast4?: string; expiry?: string; registerListed?: boolean
}
export interface KycChipEvidence { passiveAuthentication?: string; signer?: string }
export interface KycFaceEvidence { liveness?: number; match?: number }
export interface KycBankIdEvidence {
  claims: { givenName?: string; familyName?: string; birthdate?: string; gender?: string }
  matches: Record<string, boolean>
}
export interface KycRegisterEvidence { name: string; result: string; detail?: string }
export interface KycScreeningEvidence { checkId?: string; status: string; topScore?: number; lists?: string }
export interface KycRiskFactor { code: string; points?: number; label?: string }
export interface KycRiskEvidence { score?: number; rating?: string; factors: KycRiskFactor[] }
export interface KycFirstPaymentEvidence {
  payerName?: string; payerAccountMasked?: string; payerBank?: string
  amount?: string; currency?: string; nameMatch?: boolean; receivedAt?: string
}
export interface KycPidEvidence { decision: string; caseId?: string }

export interface KycEvidence {
  method?: string
  identityChecks?: KycIdentityCheck[]
  document?: KycDocumentEvidence
  chip?: KycChipEvidence
  face?: KycFaceEvidence
  bankId?: KycBankIdEvidence
  registers?: KycRegisterEvidence[]
  screening?: KycScreeningEvidence[]
  risk?: KycRiskEvidence
  firstPayment?: KycFirstPaymentEvidence
  pid?: KycPidEvidence
  /** Sections (or malformed known sections) the console could not interpret, verbatim. */
  unrecognised: Record<string, unknown>
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isTimestamp(value: unknown): value is string {
  return isText(value) && Number.isFinite(Date.parse(value))
}

const text = (value: unknown): string | undefined => (isText(value) ? value : undefined)
const num = (value: unknown): number | undefined =>
  (typeof value === 'number' && Number.isFinite(value) ? value : undefined)
const bool = (value: unknown): boolean | undefined => (typeof value === 'boolean' ? value : undefined)
/** An amount may arrive as a JSON number or a decimal string; either is shown as text. */
const amountText = (value: unknown): string | undefined =>
  (isText(value) ? value : typeof value === 'number' && Number.isFinite(value) ? String(value) : undefined)

/** Parses each element; null when ANY element is malformed, so the section falls back to raw JSON. */
function list<T>(value: unknown, parse: (item: Record<string, unknown>) => T | null): T[] | null {
  if (!Array.isArray(value)) return null
  const out: T[] = []
  for (const item of value) {
    if (!isRecord(item)) return null
    const parsed = parse(item)
    if (parsed === null) return null
    out.push(parsed)
  }
  return out
}

const SECTION_PARSERS: { [K in Exclude<keyof KycEvidence, 'unrecognised'>]: (value: unknown) => KycEvidence[K] | null } = {
  method: value => text(value) ?? null,
  identityChecks: value => list(value, item =>
    isText(item.type) && isText(item.status) ? { type: item.type, status: item.status, detail: text(item.detail) } : null),
  document: value => isRecord(value)
    ? {
        type: text(value.type), issuingCountry: text(value.issuingCountry), numberLast4: text(value.numberLast4),
        expiry: text(value.expiry), registerListed: bool(value.registerListed),
      }
    : null,
  chip: value => isRecord(value)
    ? { passiveAuthentication: text(value.passiveAuthentication), signer: text(value.signer) }
    : null,
  face: value => isRecord(value) ? { liveness: num(value.liveness), match: num(value.match) } : null,
  bankId: value => {
    if (!isRecord(value)) return null
    const claims = isRecord(value.claims) ? value.claims : {}
    const matches: Record<string, boolean> = {}
    if (isRecord(value.matches)) {
      for (const [key, match] of Object.entries(value.matches)) if (typeof match === 'boolean') matches[key] = match
    }
    return {
      claims: {
        givenName: text(claims.givenName), familyName: text(claims.familyName),
        birthdate: text(claims.birthdate), gender: text(claims.gender),
      },
      matches,
    }
  },
  registers: value => list(value, item =>
    isText(item.name) && isText(item.result) ? { name: item.name, result: item.result, detail: text(item.detail) } : null),
  screening: value => list(value, item =>
    isText(item.status)
      ? { checkId: text(item.checkId), status: item.status, topScore: num(item.topScore), lists: text(item.lists) }
      : null),
  risk: value => {
    if (!isRecord(value)) return null
    const factors = value.factors === undefined || value.factors === null
      ? []
      : list(value.factors, item => isText(item.code)
        ? { code: item.code, points: num(item.points), label: text(item.label) }
        : null)
    if (factors === null) return null
    return { score: num(value.score), rating: text(value.rating), factors }
  },
  firstPayment: value => isRecord(value)
    ? {
        payerName: text(value.payerName), payerAccountMasked: text(value.payerAccountMasked),
        payerBank: text(value.payerBank), amount: amountText(value.amount), currency: text(value.currency),
        nameMatch: bool(value.nameMatch), receivedAt: text(value.receivedAt),
      }
    : null,
  pid: value => isRecord(value) && isText(value.decision) ? { decision: value.decision, caseId: text(value.caseId) } : null,
}

/**
 * Parses the case's `evidence` object. Returns undefined when absent (an older case); never null —
 * a malformed evidence object degrades to `unrecognised` rather than invalidating the case.
 */
export function parseKycEvidence(value: unknown): KycEvidence | undefined {
  if (value === undefined || value === null) return undefined
  if (!isRecord(value)) return { unrecognised: { evidence: value } }
  const evidence: KycEvidence = { unrecognised: {} }
  for (const [key, raw] of Object.entries(value)) {
    if (raw === null || raw === undefined) continue
    const parser = (SECTION_PARSERS as Record<string, ((v: unknown) => unknown) | undefined>)[key]
    const parsed = Object.hasOwn(SECTION_PARSERS, key) && parser ? parser(raw) : null
    if (parsed === null || parsed === undefined) evidence.unrecognised[key] = raw
    else (evidence as unknown as Record<string, unknown>)[key] = parsed
  }
  return evidence
}

/** True when the evidence holds nothing to show (no section, nothing unrecognised). */
export function isEmptyKycEvidence(evidence: KycEvidence | undefined): boolean {
  if (!evidence) return true
  return Object.keys(evidence).every(key => key === 'unrecognised') && Object.keys(evidence.unrecognised).length === 0
}

export function parseKycCaseEvidence(value: unknown, requestedPartyId?: string): KycCaseEvidence | null {
  if (!isRecord(value) || !isText(value.id) || !isText(value.partyId)) return null
  if (requestedPartyId !== undefined && value.partyId !== requestedPartyId) return null
  if (!CASE_STATUSES.has(String(value.status)) || !RISK_LEVELS.has(String(value.riskLevel))) return null
  if (!isTimestamp(value.createdAt) || !isTimestamp(value.updatedAt) || !Array.isArray(value.checks)) return null

  const checks: KycCheckEvidence[] = []
  for (const check of value.checks) {
    if (!isRecord(check) || !isText(check.id)) return null
    if (!CHECK_TYPES.has(String(check.checkType)) || !CHECK_STATUSES.has(String(check.status))) return null
    if (check.result !== undefined && check.result !== null && typeof check.result !== 'string') return null
    checks.push({
      id: check.id,
      checkType: String(check.checkType),
      status: String(check.status),
      result: check.result as string | null | undefined,
    })
  }
  if (value.reviewedBy !== undefined && value.reviewedBy !== null && !isText(value.reviewedBy)) return null
  // The step-9 fields are additive and optional. A malformed one is dropped (treated as absent)
  // rather than rejecting the whole case: the case, its status and its checks are still true.
  const reviewReasons = Array.isArray(value.reviewReasons) ? value.reviewReasons.filter(isText) : undefined
  return {
    id: value.id,
    partyId: value.partyId,
    status: String(value.status),
    riskLevel: String(value.riskLevel),
    checks,
    reviewedBy: value.reviewedBy as string | null | undefined,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    evidence: parseKycEvidence(value.evidence),
    dueDiligence: text(value.dueDiligence),
    policyVersion: text(value.policyVersion),
    reviewReasons,
    decidedBy: text(value.decidedBy),
  }
}

export interface KycCasePageEvidence {
  items: KycCaseEvidence[]
  total: number
  page: number
  size: number
  statusFilter: string | null
}

export function parseKycCasePageEvidence(
  value: unknown,
  requestedPage: number,
  requestedSize: number,
): KycCasePageEvidence | null {
  if (!isRecord(value) || !Array.isArray(value.items)) return null
  if (value.page !== requestedPage || value.size !== requestedSize) return null
  if (!Number.isInteger(value.total) || (value.total as number) < 0 || value.items.length > requestedSize) return null
  if (value.statusFilter !== null && value.statusFilter !== undefined && !CASE_STATUSES.has(String(value.statusFilter))) return null
  const items = value.items.map(item => parseKycCaseEvidence(item))
  if (items.some(item => item === null)) return null
  return {
    items: items as KycCaseEvidence[], total: value.total as number,
    page: requestedPage, size: requestedSize, statusFilter: value.statusFilter == null ? null : String(value.statusFilter),
  }
}
