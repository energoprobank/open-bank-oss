// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { ReactNode } from 'react'
import { BRAND } from '@/lib/brand'
import styles from './BrandGuide.module.css'

interface BrandGuideProps {
  eyebrow?: string
  title: string
  children: ReactNode
  action?: ReactNode
  compact?: boolean
}

/**
 * A branded orientation banner (Firefly surface, Curious Blue glow, white Energo-Pro mark).
 * It is a navigation and education aid only: it deliberately does not represent AI agents,
 * system health, risk severity, or approval authority.
 */
export function BrandGuide({ eyebrow = BRAND.name, title, children, action, compact = false }: BrandGuideProps) {
  return (
    <aside className={`${styles.guide} ${compact ? styles.compact : ''}`} aria-label={title}>
      <div className={styles.copy}>
        <span className={styles.eyebrow}>{eyebrow}</span>
        <h2 className={styles.title}>{title}</h2>
        <div className={styles.body}>{children}</div>
        {action && <div className={styles.action}>{action}</div>}
      </div>
      <div className={styles.mark} aria-hidden="true">
        {/* eslint-disable-next-line @next/next/no-img-element -- static brand asset */}
        <img src={BRAND.logo.markOnDark} alt="" width={375} height={118} />
      </div>
    </aside>
  )
}
