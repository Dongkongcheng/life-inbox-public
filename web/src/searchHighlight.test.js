import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { segmentHighlightText } from './searchHighlight.js'

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
