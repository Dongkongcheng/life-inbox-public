import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  applyTodoOperationResult,
  TODO_STATUS,
  todoCalendarDate
} from './todoState.js'

test('displays dueDate as the backend calendar-date string without timezone conversion', () => {
  assert.equal(todoCalendarDate({ dueDate: '2026-08-25' }), '2026-08-25')
  assert.equal(todoCalendarDate({ dueDate: null }), null)
  assert.equal(todoCalendarDate({}), null)
})

test('removes completed Todo from OPEN and reopened Todo from COMPLETED views', () => {
  const current = [{ id: 1, status: TODO_STATUS.OPEN }, { id: 2, status: TODO_STATUS.OPEN }]
  const completed = { id: 1, status: TODO_STATUS.COMPLETED, completedTime: '2026-08-25T12:00:00' }

  assert.deepEqual(
    applyTodoOperationResult(current, completed, TODO_STATUS.OPEN),
    [current[1]]
  )
  assert.deepEqual(
    applyTodoOperationResult([completed], { id: 1, status: TODO_STATUS.OPEN }, TODO_STATUS.COMPLETED),
    []
  )
})

test('uses the authoritative backend Todo when it remains in the active view', () => {
  const current = [{ id: 1, status: TODO_STATUS.OPEN, title: '旧标题' }]
  const authoritative = { id: 1, status: TODO_STATUS.OPEN, title: '权威标题' }

  assert.deepEqual(
    applyTodoOperationResult(current, authoritative, TODO_STATUS.OPEN),
    [authoritative]
  )
})

test('Todo component separates list loading, errors, empty states and per-item operations', async () => {
  const component = await readFile(
    new URL('./components/TodoPanel.vue', import.meta.url),
    'utf8'
  )
  const app = await readFile(new URL('./App.vue', import.meta.url), 'utf8')

  assert.match(app, /<TodoPanel v-if="activeView === 'todos'"/)
  assert.match(app, />\s*Todo\s*<\/button>/)
  assert.match(component, /onMounted\(\(\) => loadTodos\(TODO_STATUS\.OPEN\)\)/)
  assert.match(component, /暂无待办/)
  assert.match(component, /暂无已完成 Todo/)
  assert.match(component, /listErrorMessage/)
  assert.match(component, /todoOperations/)
  assert.match(component, /完成中…/)
  assert.match(component, /重新打开中…/)
  assert.doesNotMatch(component, /new Date\s*\(\s*todo(?:\?|\.)dueDate/)
})
