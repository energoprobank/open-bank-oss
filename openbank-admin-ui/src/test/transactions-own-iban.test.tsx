// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import TransactionsPage from '@/app/transactions/page'

vi.mock('@/components/auth/AuthGuard', () => ({
  Can: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.unstubAllGlobals()
})

// A valid CZ IBAN of one of the bank's own accounts (bank code 8899) and a foreign one.
const OWN_IBAN = 'CZ7488990000001078047903'
const OTHER_BANK_IBAN = 'CZ6508000000192000145399'
const OWN_ACCOUNT_ID = 'f83a8b3a-ed55-4824-9596-cfd007934e49'

function stubBackend(ownAccounts: Record<string, string>) {
  const calls: string[] = []
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    calls.push(url)
    const lookup = url.match(/accounts\/iban\/([A-Z0-9]+)/)
    if (lookup) {
      const id = ownAccounts[lookup[1]]
      return id
        ? new Response(JSON.stringify({ id, accountNumber: lookup[1] }), { status: 200 })
        : new Response(JSON.stringify({ code: 'NOT_FOUND' }), { status: 404 })
    }
    return new Response(JSON.stringify({ items: [], total: 0 }), { status: 200 })
  })
  vi.stubGlobal('fetch', fetch)
  return calls
}

async function searchByIban(iban: string) {
  render(<LanguageProvider><TransactionsPage /></LanguageProvider>)
  fireEvent.change(screen.getByLabelText('Filter by IBAN'), { target: { value: iban } })
  fireEvent.click(screen.getByRole('button', { name: 'Search transactions' }))
}

const searchCall = (calls: string[]) => calls.find(url => url.includes('/transactions/search'))

describe('Transaction search by one of the bank\'s own IBANs', () => {
  it('searches by the resolved account id, so internal bookings without an IBAN are found', async () => {
    const calls = stubBackend({ [OWN_IBAN]: OWN_ACCOUNT_ID })
    await searchByIban(OWN_IBAN)

    await waitFor(() => expect(searchCall(calls)).toBeDefined())
    const url = new URL(searchCall(calls)!, 'http://localhost')
    expect(url.searchParams.get('accountId')).toBe(OWN_ACCOUNT_ID)
    expect(url.searchParams.has('iban')).toBe(false)
  })

  it('keeps the IBAN filter for an IBAN that is not one of ours', async () => {
    const calls = stubBackend({})
    await searchByIban(OTHER_BANK_IBAN)

    await waitFor(() => expect(searchCall(calls)).toBeDefined())
    const url = new URL(searchCall(calls)!, 'http://localhost')
    expect(url.searchParams.get('iban')).toBe(OTHER_BANK_IBAN)
    expect(url.searchParams.has('accountId')).toBe(false)
  })
})
