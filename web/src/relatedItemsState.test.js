import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  inboxItemTypeLabel,
  normalizeRelatedItems,
  relatedItemPreview,
  relatedItemTitle
} from './relatedItemsState.js'
import { createLatestRequestGuard } from './searchRequestGuard.js'

test('normalizes only valid nested Product DTO items', () => {
  const item = { id: 2, type: 'TEXT', title: 'B' }
  assert.deepEqual(normalizeRelatedItems([
    { relationType: 'RELATED_TO', relatedInboxItem: item },
    { relationType: 'RELATED_TO', relatedInboxItem: null },
    { relationType: 'RELATED_TO', relatedInboxItem: { id: -1 } }
  ]), [item])
})

test('uses bounded DTO text and readable existing Inbox type labels', () => {
  assert.equal(relatedItemTitle({ type: 'FILE', title: '  ' }), '未命名文件')
  assert.equal(relatedItemTitle({ type: 'TEXT', title: null }), '未命名内容')
  assert.equal(relatedItemPreview({ summary: ' AI 摘要 ', preview: '原始预览' }), 'AI 摘要')
  assert.equal(relatedItemPreview({ summary: null, preview: ' 原始预览 ' }), '原始预览')
  assert.equal(inboxItemTypeLabel('URL'), '🔗 URL')
  assert.equal(inboxItemTypeLabel('IMAGE'), '🖼️ IMAGE')
})

test('request identity rejects a late response after the active request changes', () => {
  const guard = createLatestRequestGuard()
  const requestA = guard.begin()
  const requestB = guard.begin()

  assert.equal(guard.isLatest(requestA), false)
  assert.equal(guard.isLatest(requestB), true)
  guard.invalidate()
  assert.equal(guard.isLatest(requestB), false)
})

test('Related Items panel is lazy, isolated and renders untrusted text without v-html', async () => {
  const component = await readFile(
    new URL('./components/RelatedItemsPanel.vue', import.meta.url),
    'utf8'
  )
  const app = await readFile(new URL('./App.vue', import.meta.url), 'utf8')

  assert.doesNotMatch(component, /onMounted/)
  assert.match(component, /if \(expanded\) loadRelatedItems\(\)/)
  assert.match(component, /createLatestRequestGuard/)
  assert.match(component, /正在加载相关内容…/)
  assert.match(component, /暂未发现相关内容/)
  assert.match(component, /重新加载/)
  assert.match(component, /requestGuard\.invalidate\(\)/)
  assert.doesNotMatch(component, /v-html/)
  assert.doesNotMatch(component, /score|similarity|confidence|evidence/i)
  assert.doesNotMatch(component, /relations\/(?:discover|rediscover)|relations\/backfill/)
  assert.match(app, /<RelatedItemsPanel/)
  assert.match(app, /expandedRelatedItemId/)
  assert.match(app, /document\.getElementById\(`inbox-item-\$\{inboxItemId\}`\)/)
  assert.match(app, /target\.scrollIntoView/)
  assert.doesNotMatch(app, /relations\/(?:discover|rediscover)|relations\/backfill/)
  assert.doesNotMatch(app, /relation-(?:dialog|drawer)|RelatedItemDetail/i)
})
