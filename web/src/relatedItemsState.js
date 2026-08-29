export const normalizeRelatedItems = (relations) => relations
  .map((relation) => relation?.relatedInboxItem)
  .filter((item) => Number.isInteger(item?.id) && item.id > 0)

export const relatedItemTitle = (item) => {
  const title = typeof item?.title === 'string' ? item.title.trim() : ''
  if (title) return title
  return item?.type === 'FILE' ? '未命名文件' : '未命名内容'
}

export const relatedItemPreview = (item) => {
  const summary = typeof item?.summary === 'string' ? item.summary.trim() : ''
  if (summary) return summary
  return typeof item?.preview === 'string' ? item.preview.trim() : ''
}

// 与主 Inbox 卡片沿用相同的内容类型表达，不暴露 Relation 内部枚举。
export const inboxItemTypeLabel = (type) => {
  if (type === 'URL') return '🔗 URL'
  if (type === 'FILE') return '📄 FILE'
  if (type === 'IMAGE') return '🖼️ IMAGE'
  return type === 'TEXT' ? 'TEXT' : '内容'
}
