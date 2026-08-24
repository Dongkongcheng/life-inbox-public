import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { segmentHighlightText } from './searchHighlight.js'
import { createLatestRequestGuard } from './searchRequestGuard.js'

test('highlights Chinese text while preserving surrounding content', () => {
  assert.deepEqual(segmentHighlightText('学习 Redis 分布式锁', '分布式'), [
    { text: '学习 Redis ', matched: false },
    { text: '分布式', matched: true },
    { text: '锁', matched: false }
  ])
})

test('matches English without changing the original letter case', () => {
  assert.deepEqual(segmentHighlightText('REDIS and redis', 'Redis'), [
    { text: 'REDIS', matched: true },
    { text: ' and ', matched: false },
    { text: 'redis', matched: true }
  ])
})

test('treats regular-expression and HTML-looking characters as plain text', () => {
  const source = 'literal % _ <script> & " . text'

  for (const query of ['%', '_', '<script>', '&', '"', '.']) {
    const segments = segmentHighlightText(source, query)
    assert.equal(segments.map((segment) => segment.text).join(''), source)
    assert.deepEqual(segments.filter((segment) => segment.matched), [
      { text: query, matched: true }
    ])
  }
})

test('highlight component renders escaped Vue text nodes and never uses v-html', async () => {
  const component = await readFile(
    new URL('./components/HighlightedText.vue', import.meta.url),
    'utf8'
  )

  assert.doesNotMatch(component, /v-html/)
  assert.match(component, /\{\{ segment\.text \}\}/)
})

test('search UI exposes hybrid mode and refreshes from the active mode', async () => {
  const app = await readFile(new URL('./App.vue', import.meta.url), 'utf8')

  assert.match(app, /<option value="hybrid">混合<\/option>/)
  assert.match(app, /params\.set\('mode', activeSearchMode\.value\)/)
  assert.match(app, /activeSearchMode\.value = searchMode\.value/)
  assert.doesNotMatch(app, /v-html/)
})

test('only the latest search request may apply results', async () => {
  const guard = createLatestRequestGuard()
  const appliedResults = []
  const firstRequest = guard.begin()

  const slowOldResponse = new Promise((resolve) => {
    setTimeout(() => {
      if (guard.isLatest(firstRequest)) appliedResults.push('old')
      resolve()
    }, 10)
  })

  const latestRequest = guard.begin()
  if (guard.isLatest(latestRequest)) appliedResults.push('latest')
  await slowOldResponse

  assert.deepEqual(appliedResults, ['latest'])
})
