// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Admin-UI copy of branding/brand.yaml — the repo-wide source of truth. This app's Docker build
// context is openbank-admin-ui/, so it cannot read that file; keep the two in step.
export const BRAND = {
  name: 'Energo-Pro Bank',
  shortName: 'Energo-Pro',
  adminPortal: { en: 'Energo-Pro Bank Admin Portal', cs: 'Energo-Pro Bank Administrace' },
  // PLACEHOLDERS (brand.yaml `bank.legal_name`, `contact.support_email`): the legal entity and
  // mail domain are not decided yet; `.example` never routes.
  legalName: 'Energo-Pro Bank a.s.',
  supportEmail: 'support@energoprobank.example',
  logo: {
    onDark: '/brand/energopro/logo-white.png',
    onLight: '/brand/energopro/logo-firefly.png',
    markOnDark: '/brand/energopro/mark-white.png',
    mark: '/brand/energopro/mark-blue.png',
  },
} as const
