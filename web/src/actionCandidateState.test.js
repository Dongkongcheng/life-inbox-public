import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  ACTION_CANDIDATE_STATUS,
  actionCandidateDeadline,
  actionCandidateTypeLabel,
  pendingActionCandidates,
  replaceActionCandidate,
  visibleActionCandidates
} from './actionCandidateState.js'

test('displays normalized deadline as a calendar-date string without timezone conversion', () => {
  assert.deepEqual(actionCandidateDeadline({
    deadline: '2026-08-25',
    deadlineText: '8月25日前'
  }), {
    text: '2026-08-25',
    unresolved: false
  })
})

test('falls back to original deadline text instead of guessing a year', () => {
  assert.deepEqual(actionCandidateDeadline({
    deadline: null,
    deadlineText: '8月25日前'
  }), {
    text: '8月25日前',
    unresolved: true
  })
  assert.equal(actionCandidateDeadline({ deadline: null, deadlineText: null }), null)
})

test('keeps all pending candidates while dismissed candidates leave the visible review area', () => {
  const candidates = [
    { id: 1, status: ACTION_CANDIDATE_STATUS.PENDING },
    { id: 2, status: ACTION_CANDIDATE_STATUS.PENDING },
    { id: 3, status: ACTION_CANDIDATE_STATUS.ACCEPTED },
    { id: 4, status: ACTION_CANDIDATE_STATUS.DISMISSED }
  ]

  assert.deepEqual(pendingActionCandidates(candidates).map(({ id }) => id), [1, 2])
  assert.deepEqual(visibleActionCandidates(candidates).map(({ id }) => id), [1, 2, 3])
})

test('applies backend decision response without changing unrelated candidates', () => {
  const current = [
    { id: 1, status: ACTION_CANDIDATE_STATUS.PENDING },
    { id: 2, status: ACTION_CANDIDATE_STATUS.PENDING }
  ]
  const accepted = { id: 1, status: ACTION_CANDIDATE_STATUS.ACCEPTED }

  assert.deepEqual(replaceActionCandidate(current, accepted), [accepted, current[1]])
  assert.equal(actionCandidateTypeLabel('TODO'), '待办')
  assert.equal(actionCandidateTypeLabel('DEADLINE'), '截止事项')
})

test('candidate panel stays demand-loaded and never parses a deadline with JavaScript Date', async () => {
  const component = await readFile(
    new URL('./components/ActionCandidatePanel.vue', import.meta.url),
    'utf8'
  )

  assert.doesNotMatch(component, /onMounted/)
  assert.doesNotMatch(component, /new Date\s*\(/)
  assert.match(component, /action-candidates\/extract/)
  assert.match(component, /action-candidates\/\$\{candidate\.id\}\/accept/)
  assert.match(component, /action-candidates\/\$\{candidate\.id\}\/dismiss/)
})
