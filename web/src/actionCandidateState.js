export const ACTION_CANDIDATE_STATUS = Object.freeze({
  PENDING: 'PENDING',
  ACCEPTED: 'ACCEPTED',
  DISMISSED: 'DISMISSED'
})

const actionTypeLabels = Object.freeze({
  TODO: '待办',
  DEADLINE: '截止事项'
})

export const actionCandidateTypeLabel = (type) => actionTypeLabels[type] || type || '行动'

/**
 * deadline 是后端归一化后的 Calendar Date，直接展示字符串可避免 Date/UTC 时区偏移。
 * 无法归一化时只展示原始 deadlineText，并明确提示用户仍需确认日期。
 */
export const actionCandidateDeadline = (candidate) => {
  if (candidate?.deadline) {
    return { text: String(candidate.deadline), unresolved: false }
  }
  if (candidate?.deadlineText) {
    return { text: String(candidate.deadlineText), unresolved: true }
  }
  return null
}

export const pendingActionCandidates = (candidates) => Array.isArray(candidates)
  ? candidates.filter((candidate) => candidate?.status === ACTION_CANDIDATE_STATUS.PENDING)
  : []

// 第一版不制作历史页面；DISMISSED 退出待处理区，ACCEPTED 仅保留轻量成功状态。
export const visibleActionCandidates = (candidates) => Array.isArray(candidates)
  ? candidates.filter((candidate) => candidate?.status !== ACTION_CANDIDATE_STATUS.DISMISSED)
  : []

export const replaceActionCandidate = (candidates, replacement) => {
  const current = Array.isArray(candidates) ? candidates : []
  if (!replacement?.id) return current

  let replaced = false
  const next = current.map((candidate) => {
    if (candidate?.id !== replacement.id) return candidate
    replaced = true
    return replacement
  })
  return replaced ? next : [...next, replacement]
}
