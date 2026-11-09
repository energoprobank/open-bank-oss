// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { createServer, type Server } from 'node:http'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { dockerHealthByContainer } from '@/lib/health/dockerHealth'

let server: Server | undefined

function engine(status: number, body: unknown): Promise<string> {
  const socketPath = join(mkdtempSync(join(tmpdir(), 'dh-')), 'docker.sock')
  server = createServer((req, res) => {
    expect(req.url).toBe('/containers/json?all=1')
    res.writeHead(status, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(body))
  })
  return new Promise(resolve => server!.listen(socketPath, () => resolve(socketPath)))
}

afterEach(() => new Promise<void>(resolve => (server ? server.close(() => resolve()) : resolve())))

describe('dockerHealthByContainer', () => {
  it('maps each container to the verdict of its own healthcheck', async () => {
    const socket = await engine(200, [
      { Names: ['/openbank-account-service'], State: 'running', Status: 'Up 2 hours (healthy)' },
      { Names: ['/openbank-ledger-service'], State: 'running', Status: 'Up 5 minutes (unhealthy)' },
      { Names: ['/openbank-sca-service'], State: 'running', Status: 'Up 10 seconds (health: starting)' },
      { Names: ['/openbank-kafka'], State: 'running', Status: 'Up 3 days' },
      { Names: ['/openbank-fx-service'], State: 'exited', Status: 'Exited (137) 1 hour ago' },
    ])
    const health = await dockerHealthByContainer(socket)
    expect(Object.fromEntries(health!)).toEqual({
      'openbank-account-service': 'healthy',
      'openbank-ledger-service': 'unhealthy',
      'openbank-sca-service': 'starting',
      'openbank-kafka': 'no-healthcheck',
      'openbank-fx-service': 'not-running',
    })
  })

  it('answers null, so the caller falls back to probing, when the engine refuses or is absent', async () => {
    expect(await dockerHealthByContainer(await engine(500, { message: 'boom' }))).toBeNull()
    expect(await dockerHealthByContainer(join(tmpdir(), 'no-such-docker.sock'))).toBeNull()
  })
})
