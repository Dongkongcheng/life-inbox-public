const parseResponseError = async (response, fallbackMessage) => {
  let message = fallbackMessage
  try {
    const problem = await response.json()
    message = problem.detail || problem.message || fallbackMessage
  } catch {
    // 错误响应不保证是 JSON，保留当前操作对应的安全提示。
  }
  return new Error(message)
}

const requestJson = async (endpoint, options, fallbackMessage) => {
  const response = await fetch(endpoint, options)
  if (!response.ok) throw await parseResponseError(response, fallbackMessage)
  return response.json()
}

export const getTodos = (status) => {
  const query = new URLSearchParams({ status })
  return requestJson(`/api/todos?${query.toString()}`, undefined, 'Todo 列表加载失败，请稍后重试。')
}

/** 来源上下文只在用户展开单条 Todo 时读取，不能加入列表请求。 */
export const getTodoSource = (todoId) => requestJson(
  `/api/todos/${todoId}/source`,
  undefined,
  'Todo 来源加载失败，请稍后重试。'
)

export const completeTodo = (todoId) => requestJson(
  `/api/todos/${todoId}/complete`,
  { method: 'POST' },
  'Todo 完成失败，请稍后重试。'
)

export const reopenTodo = (todoId) => requestJson(
  `/api/todos/${todoId}/reopen`,
  { method: 'POST' },
  'Todo 重新打开失败，请稍后重试。'
)
