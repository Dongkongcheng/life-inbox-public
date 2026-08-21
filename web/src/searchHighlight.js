/**
 * 只把命中的普通文本分段，组件继续交给 Vue 转义文本节点，避免把查询或内容拼成 HTML。
 */
export const segmentHighlightText = (value, query) => {
  const text = value == null ? '' : String(value)
  const needle = query == null ? '' : String(query)
  if (!text || !needle) return text ? [{ text, matched: false }] : []

  const escapedNeedle = needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matcher = new RegExp(escapedNeedle, 'giu')
  const segments = []
  let previousEnd = 0

  for (const match of text.matchAll(matcher)) {
    const matchStart = match.index
    if (matchStart > previousEnd) {
      segments.push({ text: text.slice(previousEnd, matchStart), matched: false })
    }
    segments.push({ text: match[0], matched: true })
    previousEnd = matchStart + match[0].length
  }

  if (previousEnd === 0) return [{ text, matched: false }]
  if (previousEnd < text.length) {
    segments.push({ text: text.slice(previousEnd), matched: false })
  }
  return segments
}
