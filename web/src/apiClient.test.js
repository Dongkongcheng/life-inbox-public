import assert from 'node:assert/strict'
import test from 'node:test'

import { parseResponseError, requestJson } from './apiClient.js'

test('shared JSON client returns parsed success and preserves request options', async (t) => {
  const originalFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = originalFetch })
  const calls = []
  globalThis.fetch = async (endpoint, options) => {
    calls.push({ endpoint, options })
    return { ok: true, json: async () => ({ id: 1 }) }
  }

  assert.deepEqual(
    await requestJson('/api/example', { method: 'POST' }, '请求失败'),
    { id: 1 }
  )
  assert.deepEqual(calls, [
    { endpoint: '/api/example', options: { method: 'POST' } }
  ])
})

test('shared JSON client prefers safe backend detail and exposes status', async () => {
  const error = await parseResponseError({
    status: 409,
    json: async () => ({ detail: '状态已变化' })
  }, '请求失败')

  assert.equal(error.message, '状态已变化')
  assert.equal(error.status, 409)
})

test('shared JSON client keeps fallback when the error body is not JSON', async () => {
  const error = await parseResponseError({
    status: 503,
    json: async () => { throw new SyntaxError('invalid JSON') }
  }, '服务暂不可用')

  assert.equal(error.message, '服务暂不可用')
  assert.equal(error.status, 503)
})
