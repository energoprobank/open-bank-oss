// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const sessionState: { roles: string[] } = { roles: ['ROLE_KYC_REVIEWER'] }
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { id: 'sub-1', name: 'Reviewer', roles: sessionState.roles } }, status: 'authenticated' }),
}))

import { NationalIdReveal, NATIONAL_ID_REVEAL_MS } from '@/components/party/NationalIdReveal'

const CLEAR = '9108130123'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

function mount() {
  return render(<LanguageProvider><NationalIdReveal partyId="party-1" maskedTaxId="910813/****" /></LanguageProvider>)
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('openbank-admin-lang', 'en')
  sessionState.roles = ['ROLE_KYC_REVIEWER']
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('NationalIdReveal', () => {
  it('shows the masked value as returned and no reveal control without kyc:approve', async () => {
    sessionState.roles = ['ROLE_OPERATOR']
    await act(async () => { mount() })
    expect(screen.getByTestId('national-id-value')).toHaveTextContent('910813/****')
    expect(screen.queryByRole('button', { name: /reveal/i })).toBeNull()
  })

  it('reveals the clear number via the BFF without caching or putting it in a URL, then hides it on click', async () => {
    const fetchMock = vi.fn(async () => json({ partyId: 'party-1', market: 'CZ', nationalId: CLEAR }))
    vi.stubGlobal('fetch', fetchMock)
    await act(async () => { mount() })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /reveal/i })) })
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/svc/party-service/api/v1/parties/party-1/identity/national-id')
    expect(init.cache).toBe('no-store')
    expect(screen.getByTestId('national-id-value')).toHaveTextContent(CLEAR)
    expect(screen.getByTestId('national-id-logged')).toHaveTextContent('Access is logged')
    fireEvent.click(screen.getByRole('button', { name: /hide/i }))
    expect(screen.getByTestId('national-id-value')).toHaveTextContent('910813/****')
    expect(screen.queryByTestId('national-id-logged')).toBeNull()
  })

  it('hides the clear number again after 30 seconds', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.stubGlobal('fetch', vi.fn(async () => json({ partyId: 'party-1', market: 'CZ', nationalId: CLEAR })))
    await act(async () => { mount() })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /reveal/i })) })
    expect(screen.getByTestId('national-id-value')).toHaveTextContent(CLEAR)
    await act(async () => { vi.advanceTimersByTime(NATIONAL_ID_REVEAL_MS - 1) })
    expect(screen.getByTestId('national-id-value')).toHaveTextContent(CLEAR)
    await act(async () => { vi.advanceTimersByTime(1) })
    expect(screen.getByTestId('national-id-value')).toHaveTextContent('910813/****')
  })

  it('shows a 403 inline as a permission message and keeps the mask', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 403 })))
    await act(async () => { mount() })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /reveal/i })) })
    expect(screen.getByRole('alert')).toHaveTextContent('not permitted')
    expect(screen.getByTestId('national-id-value')).toHaveTextContent('910813/****')
  })

  it('refuses a response for another party', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ partyId: 'party-2', market: 'CZ', nationalId: CLEAR })))
    await act(async () => { mount() })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /reveal/i })) })
    expect(screen.getByTestId('national-id-value')).not.toHaveTextContent(CLEAR)
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })
})
