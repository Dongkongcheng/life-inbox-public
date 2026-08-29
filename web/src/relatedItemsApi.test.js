import assert from 'node:assert/strict'
import test from 'node:test'

import { getRelatedItems, RELATED_ITEMS_LIMIT } from './relatedItemsApi.js'

test('Related Items API client uses the bounded Java Product API', async (t) => {
  const originalFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = originalFetch })
  const calls = []
  globalThis.fetch = async (endpoint, options) => {
    calls.push({ endpoint, options })
    return { ok: true, json: async () => [] }
  }

  assert.equal(RELATED_ITEMS_LIMIT, 10)
  assert.deepEqual(await getRelatedItems(123), [])
  assert.deepEqual(calls, [
    { endpoint: '/api/inbox/123/related?limit=10', options: undefined }
  ])
})

test('Related Items API failures preserve backend detail for the local error state', async (t) => {
  const originalFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = originalFetch })
  globalThis.fetch = async () => ({
    ok: false,
    json: async () => ({ detail: 'InboxItem 不存在' })
  })

  await assert.rejects(() => getRelatedItems(999), { message: 'InboxItem 不存在' })
})
