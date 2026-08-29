/** 统一解析 Java/FastAPI 风格的安全 JSON 错误，并保留 HTTP 状态供业务冲突处理。 */
export const parseResponseError = async (response, fallbackMessage) => {
  let message = fallbackMessage
  try {
    const problem = await response.json()
    message = problem.detail || problem.message || fallbackMessage
  } catch {
    // 错误正文不保证是 JSON，保留调用方提供的安全提示。
  }
  const error = new Error(message)
  error.status = response.status
  return error
}

export const requestJson = async (endpoint, options, fallbackMessage) => {
  const response = await fetch(endpoint, options)
  if (!response.ok) throw await parseResponseError(response, fallbackMessage)
  return response.json()
}
