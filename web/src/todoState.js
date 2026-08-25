export const TODO_STATUS = Object.freeze({
  OPEN: 'OPEN',
  COMPLETED: 'COMPLETED'
})

/** dueDate 是 Calendar Date，直接展示后端字符串，不能经过 Date/UTC 转换。 */
export const todoCalendarDate = (todo) => todo?.dueDate ? String(todo.dueDate) : null

export const applyTodoOperationResult = (todos, authoritativeTodo, visibleStatus) => {
  const current = Array.isArray(todos) ? todos : []
  if (!authoritativeTodo?.id) return current

  if (authoritativeTodo.status !== visibleStatus) {
    return current.filter((todo) => todo?.id !== authoritativeTodo.id)
  }
  return current.map((todo) => todo?.id === authoritativeTodo.id ? authoritativeTodo : todo)
}
