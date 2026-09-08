import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  applyTodoOperationResult,
  candidateCalendarDate,
  hasTodoSourceReference,
  TODO_STATUS,
  todoCalendarDate
} from './todoState.js'

test('displays dueDate as the backend calendar-date string without timezone conversion', () => {
  assert.equal(todoCalendarDate({ dueDate: '2026-08-25' }), '2026-08-25')
  assert.equal(todoCalendarDate({ dueDate: null }), null)
  assert.equal(todoCalendarDate({}), null)
})

test('detects source references without requiring both source rows', () => {
  assert.equal(hasTodoSourceReference({ sourceInboxItemId: 1, sourceActionCandidateId: null }), true)
  assert.equal(hasTodoSourceReference({ sourceInboxItemId: null, sourceActionCandidateId: 2 }), true)
  assert.equal(hasTodoSourceReference({ sourceInboxItemId: null, sourceActionCandidateId: null }), false)
  assert.equal(hasTodoSourceReference(null), false)
})

test('shows candidate deadline as backend calendar date and leaves unresolved dates unknown', () => {
  assert.equal(candidateCalendarDate({ deadline: '2026-08-25' }), '2026-08-25')
  assert.equal(candidateCalendarDate({ deadline: null }), null)
  assert.equal(candidateCalendarDate({}), null)
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

  assert.match(app, /:status="activeTodoStatus"/)
  assert.match(app, /@status-change="syncTodoStatus"/)
  assert.match(app, /switchView\('todos', TODO_STATUS\.OPEN\)/)
  assert.match(app, /switchView\('todos', TODO_STATUS\.COMPLETED\)/)
  assert.match(app, /<span>待完成<\/span>/)
  assert.match(app, /<span>已完成<\/span>/)
  assert.match(component, /const props = defineProps/)
  assert.match(component, /emit\('status-change', status\)/)
  assert.match(component, /\(\) => props\.status/)
  assert.match(component, /onMounted\(\(\) => loadTodos\(props\.status\)\)/)
  assert.match(component, /暂无待办/)
  assert.match(component, /暂无已完成 Todo/)
  assert.match(component, /listErrorMessage/)
  assert.match(component, /todoOperations/)
  assert.match(component, /完成中…/)
  assert.match(component, /重新打开中…/)
  assert.doesNotMatch(component, /new Date\s*\(\s*todo(?:\?|\.)dueDate/)
})

test('Todo source UI is per-item, lazy, cacheable and isolated from list lifecycle', async () => {
  const component = await readFile(
    new URL('./components/TodoPanel.vue', import.meta.url),
    'utf8'
  )

  assert.match(component, /const todoSources = ref\(\{\}\)/)
  assert.match(component, /const toggleTodoSource = \(todo\) =>/)
  assert.match(component, /if \(current\.loading \|\| current\.loaded\) return/)
  assert.match(component, /if \(expanded\) loadTodoSource\(todo\.id\)/)
  assert.match(component, /getTodoSource\(todoId\)/)
  assert.match(component, /查看来源/)
  assert.match(component, /无来源/)
  assert.match(component, /原始来源已不可用，Todo 仍可正常使用。/)
  assert.match(component, /正在加载来源…/)
  assert.match(component, /Todo 来源加载失败/)
  assert.match(component, /日期原文/)
  assert.match(component, /归一化日期/)
  assert.match(component, /未确定/)
  assert.match(component, /依据：/)
  assert.match(component, /v-if="hasTodoSourceReference\(todo\)"/)
  assert.doesNotMatch(component, /onMounted\([^)]*getTodoSource/)
  assert.doesNotMatch(component, /new Date\s*\([^)]*(?:deadline|dueDate)/)
})
