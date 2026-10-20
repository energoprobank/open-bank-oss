// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { BrandGuide } from '@/components/brand/BrandGuide'
import { BRAND } from '@/lib/brand'

describe('BrandGuide', () => {
  it('labels the banner by its title and shows the decorative Energo-Pro mark', () => {
    const { container } = render(
      <BrandGuide title="Start with a person">Search naturally.</BrandGuide>,
    )

    expect(container.querySelector('aside')).toHaveAttribute('aria-label', 'Start with a person')
    expect(container.querySelector('img')).toHaveAttribute('src', BRAND.logo.markOnDark)
    expect(container.querySelector('img')).toHaveAttribute('alt', '')
    expect(container).toHaveTextContent(BRAND.name)
  })
})
