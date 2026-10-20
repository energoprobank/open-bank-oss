// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { Metadata } from 'next'
import { ReferralLanding } from './ReferralLanding'
import { BRAND } from '@/lib/brand'

export const metadata: Metadata = {
  title: `You've been invited — ${BRAND.name}`,
  description: `A friend invited you to ${BRAND.name}. Open an account and you both get rewarded.`,
}

// Public member-get-member (referral) landing page served at /r/<token>. Must stay
// reachable without a session — see isPublicSurface (the proxy gate delegates to it).
export default function ReferralLandingPage() {
  return <ReferralLanding />
}
