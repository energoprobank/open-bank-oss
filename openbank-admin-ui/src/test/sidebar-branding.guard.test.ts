import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const sidebar = readFileSync(path.resolve(__dirname, '../components/layout/Sidebar.tsx'), 'utf8')

describe('sidebar product branding contract', () => {
  it('does not advertise a stale hard-coded platform version', () => {
    expect(sidebar).not.toContain('OpenBank v2.0')
    // The portal name comes from the single brand definition, never a literal in the sidebar.
    expect(sidebar).toContain('BRAND.adminPortal.en')
    expect(sidebar).toContain('BRAND.adminPortal.cs')
    expect(sidebar).not.toMatch(/>\s*OpenBank\s*</)
  })
})
