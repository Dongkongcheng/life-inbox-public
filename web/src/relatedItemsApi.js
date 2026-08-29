const parseResponseError = async (response, fallbackMessage) => {
  let message = fallbackMessage
  try {
    const problem = await response.json()
    message = problem.detail || problem.message || fallbackMessage
  } catch {
    // Related GET 的错误正文不保证是 JSON，保留局部面板使用的安全提示。
  }
  return new Error(message)
}

export const RELATED_ITEMS_LIMIT = 10

/** Related Items 只读取 Java Product API，不从浏览器触发任何关系发现。 */
export const getRelatedItems = async (inboxItemId) => {
  const query = new URLSearchParams({ limit: String(RELATED_ITEMS_LIMIT) })
  const response = await fetch(`/api/inbox/${inboxItemId}/related?${query.toString()}`)
  if (!response.ok) {
    throw await parseResponseError(response, '相关内容加载失败，请稍后重试。')
  }
  return response.json()
}
