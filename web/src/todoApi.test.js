import assert from 'node:assert/strict'
import test from 'node:test'

import { completeTodo, getTodoSource, getTodos, reopenTodo } from './todoApi.js'

test('Todo API client uses list status and explicit lifecycle routes', async (t) => {
  const originalFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = originalFetch })
  const calls = []
  globalThis.fetch = async (endpoint, options) => {
    calls.push({ endpoint, options })
    return { ok: true, json: async () => ({ id: 1 }) }
  }

  await getTodos('OPEN')
  await getTodoSource(1)
  await completeTodo(1)
  await reopenTodo(1)

  assert.deepEqual(calls, [
    { endpoint: '/api/todos?status=OPEN', options: undefined },
    { endpoint: '/api/todos/1/source', options: undefined },
    { endpoint: '/api/todos/1/complete', options: { method: 'POST' } },
    { endpoint: '/api/todos/1/reopen', options: { method: 'POST' } }
  ])
})

test('Todo API failures expose backend detail without manufacturing successful state', async (t) => {
  const originalFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = originalFetch })
  globalThis.fetch = async () => ({
    ok: false,
    json: async () => ({ detail: 'Todo 不存在' })
  })

  await assert.rejects(() => completeTodo(99), { message: 'Todo 不存在' })
  await assert.rejects(() => reopenTodo(99), { message: 'Todo 不存在' })
  await assert.rejects(() => getTodoSource(99), { message: 'Todo 不存在' })
})
