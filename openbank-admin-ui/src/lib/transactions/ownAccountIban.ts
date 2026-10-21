// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { normalizeIban } from '@/lib/validation/iban'
import { svcUrl } from '@/lib/services/bff'

/**
 * Resolve an IBAN to the id of one of THIS bank's accounts, or null when it is not ours (or the
 * lookup is unavailable).
 *
 * Why: transaction-service's `iban` filter matches the IBAN stored ON a transaction, and a booking
 * between two of the bank's own accounts (internal transfer, welcome bonus, own-account sweep)
 * stores only source/target account ids. An operator pasting a customer's own IBAN therefore got
 * an empty result although the account has activity. Searching by the resolved account id finds
 * everything booked to that account; an IBAN that resolves to nothing keeps the IBAN filter, which
 * is right for other banks' accounts.
 */
export async function resolveOwnAccountId(iban: string, signal?: AbortSignal): Promise<string | null> {
  const normalized = normalizeIban(iban)
  if (!normalized) return null
  try {
    const res = await fetch(
      svcUrl('account-service', `/api/v1/accounts/iban/${encodeURIComponent(normalized)}`),
      { cache: 'no-store', signal },
    )
    if (!res.ok) return null
    const body = await res.json() as unknown
    const id = (body as { id?: unknown } | null)?.id
    return typeof id === 'string' && id.length > 0 ? id : null
  } catch (error) {
    if (signal?.aborted) throw error
    return null
  }
}
