import { requestJson } from './apiClient.js'

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
