import { requestJson } from './apiClient.js'

export const RELATED_ITEMS_LIMIT = 10

/** Related Items 只读取 Java Product API，不从浏览器触发任何关系发现。 */
export const getRelatedItems = (inboxItemId) => {
  const query = new URLSearchParams({ limit: String(RELATED_ITEMS_LIMIT) })
  return requestJson(
    `/api/inbox/${inboxItemId}/related?${query.toString()}`,
    undefined,
    '相关内容加载失败，请稍后重试。'
  )
}
