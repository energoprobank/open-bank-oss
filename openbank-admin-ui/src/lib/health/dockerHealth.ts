// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { request } from 'node:http'

// Local docker-compose only. Every service serves /q/health/ready on its management port (8085), which
// compose does not publish to the host, so an admin-ui running on the host cannot probe it and the whole
// fleet reads DOWN. Docker already runs that exact probe as each container's healthcheck; when
// LOCAL_DOCKER_SOCKET points at the engine socket we read its verdict instead of probing.

export type DockerHealth = 'healthy' | 'unhealthy' | 'starting' | 'no-healthcheck' | 'not-running'

interface DockerContainer { Names?: string[]; State?: string; Status?: string }

export function dockerSocket(): string | undefined {
  return process.env.LOCAL_DOCKER_SOCKET || undefined
}

function healthOf(c: DockerContainer): DockerHealth {
  if (c.State !== 'running') return 'not-running'
  const status = c.Status ?? ''
  if (status.includes('(healthy)')) return 'healthy'
  if (status.includes('(unhealthy)')) return 'unhealthy'
  if (status.includes('(health: starting)')) return 'starting'
  return 'no-healthcheck'
}

/** Container name → health, or null when the engine cannot be asked (the caller then probes as before). */
export function dockerHealthByContainer(socketPath: string, timeoutMs = 3000): Promise<Map<string, DockerHealth> | null> {
  return new Promise((resolve) => {
    const req = request({ socketPath, path: '/containers/json?all=1', method: 'GET', timeout: timeoutMs }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (chunk: string) => { body += chunk })
      res.on('end', () => {
        if (res.statusCode !== 200) return resolve(null)
        try {
          const map = new Map<string, DockerHealth>()
          for (const c of JSON.parse(body) as DockerContainer[]) {
            for (const name of c.Names ?? []) map.set(name.replace(/^\//, ''), healthOf(c))
          }
          resolve(map)
        } catch {
          resolve(null)
        }
      })
    })
    req.on('timeout', () => req.destroy())
    req.on('error', () => resolve(null))
    req.end()
  })
}
