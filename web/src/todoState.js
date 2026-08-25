export const TODO_STATUS = Object.freeze({
  OPEN: 'OPEN',
  COMPLETED: 'COMPLETED'
})

/** dueDate 是 Calendar Date，直接展示后端字符串，不能经过 Date/UTC 转换。 */
export const todoCalendarDate = (todo) => todo?.dueDate ? String(todo.dueDate) : null

export const hasTodoSourceReference = (todo) => Boolean(
  todo?.sourceInboxItemId || todo?.sourceActionCandidateId
)

/** Candidate deadline 同样是 Calendar Date；空值必须显示未确定，不能由浏览器猜测。 */
export const candidateCalendarDate = (candidate) => candidate?.deadline
  ? String(candidate.deadline)
  : null

export const applyTodoOperationResult = (todos, authoritativeTodo, visibleStatus) => {
  const current = Array.isArray(todos) ? todos : []
  if (!authoritativeTodo?.id) return current

  if (authoritativeTodo.status !== visibleStatus) {
    return current.filter((todo) => todo?.id !== authoritativeTodo.id)
  }
  return current.map((todo) => todo?.id === authoritativeTodo.id ? authoritativeTodo : todo)
}
