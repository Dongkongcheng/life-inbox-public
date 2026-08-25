<script setup>
import { computed, ref } from 'vue'
import {
  ACTION_CANDIDATE_STATUS,
  actionCandidateDeadline,
  actionCandidateTypeLabel,
  pendingActionCandidates,
  replaceActionCandidate,
  visibleActionCandidates
} from '../actionCandidateState.js'

const props = defineProps({
  inboxItemId: {
    type: [Number, String],
    required: true
  },
  disabled: {
    type: Boolean,
    default: false
  }
})

const candidates = ref([])
const loaded = ref(false)
const loadingCandidates = ref(false)
const extracting = ref(false)
const candidateOperations = ref({})
const candidateNotices = ref({})
const errorMessage = ref('')
const successMessage = ref('')

const pendingCandidates = computed(() => pendingActionCandidates(candidates.value))
const visibleCandidates = computed(() => visibleActionCandidates(candidates.value))
const hasDecisionInProgress = computed(
  () => Object.keys(candidateOperations.value).length > 0
)
const panelBusy = computed(
  () => loadingCandidates.value || extracting.value || hasDecisionInProgress.value
)

const operationFor = (candidateId) => candidateOperations.value[candidateId]
const isCandidateProcessing = (candidateId) => Boolean(operationFor(candidateId))
const beginCandidateOperation = (candidateId, operation) => {
  candidateOperations.value = {
    ...candidateOperations.value,
    [candidateId]: operation
  }
}
const finishCandidateOperation = (candidateId) => {
  const next = { ...candidateOperations.value }
  delete next[candidateId]
  candidateOperations.value = next
}

const parseResponseError = async (response, fallbackMessage) => {
  let message = fallbackMessage
  try {
    const problem = await response.json()
    message = problem.detail || problem.message || message
  } catch {
    // 产品错误不保证带 JSON，保留当前操作对应的安全提示。
  }
  const error = new Error(message)
  error.status = response.status
  return error
}

const requestJson = async (endpoint, options, fallbackMessage) => {
  const response = await fetch(endpoint, options)
  if (!response.ok) throw await parseResponseError(response, fallbackMessage)
  return response.json()
}

const loadCandidates = async ({ preserveMessages = false } = {}) => {
  if (loadingCandidates.value || extracting.value) return false
  loadingCandidates.value = true
  if (!preserveMessages) {
    errorMessage.value = ''
    successMessage.value = ''
  }

  try {
    const result = await requestJson(
      `/api/inbox/${props.inboxItemId}/action-candidates`,
      undefined,
      '加载行动失败，请稍后重试。'
    )
    if (!Array.isArray(result)) throw new Error('行动数据格式无效，请稍后重试。')
    candidates.value = result
    loaded.value = true
    return true
  } catch (error) {
    console.error(error)
    // 查询失败时不清空已展示的 Candidate，避免一次网络错误造成状态消失。
    errorMessage.value = error.message || '加载行动失败，请稍后重试。'
    return false
  } finally {
    loadingCandidates.value = false
  }
}

const extractCandidates = async () => {
  if (extracting.value || loadingCandidates.value || hasDecisionInProgress.value || props.disabled) {
    return
  }
  extracting.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    const result = await requestJson(
      `/api/inbox/${props.inboxItemId}/action-candidates/extract`,
      { method: 'POST' },
      '检测行动失败，请稍后重试。'
    )
    if (!Array.isArray(result)) throw new Error('行动检测结果格式无效，请稍后重试。')
    // Extraction 已返回完整 DTO，直接采用响应；失败路径绝不预先清空旧 Candidate。
    candidates.value = result
    candidateNotices.value = {}
    loaded.value = true
    const pendingCount = pendingActionCandidates(result).length
    if (pendingCount > 0) {
      successMessage.value = `检测完成，发现 ${pendingCount} 个待处理行动。`
    } else if (visibleActionCandidates(result).length > 0) {
      successMessage.value = '检测完成，暂未发现新的待处理行动。'
    }
  } catch (error) {
    console.error(error)
    errorMessage.value = error.message || '检测行动失败，请稍后重试。'
  } finally {
    extracting.value = false
  }
}

const refreshAfterConflict = async (message) => {
  const refreshed = await loadCandidates({ preserveMessages: true })
  errorMessage.value = refreshed
    ? `${message}，已刷新当前行动状态。`
    : `${message}；刷新当前行动状态失败，请稍后重试。`
}

const acceptCandidate = async (candidate) => {
  if (isCandidateProcessing(candidate.id) || extracting.value || props.disabled) return
  beginCandidateOperation(candidate.id, 'accept')
  errorMessage.value = ''
  successMessage.value = ''

  try {
    // 前端只调用原子 Accept API；Todo 创建、来源映射和状态机全部由 Java 业务事务负责。
    const result = await requestJson(
      `/api/inbox/${props.inboxItemId}/action-candidates/${candidate.id}/accept`,
      { method: 'POST' },
      '创建 Todo 失败，请稍后重试。'
    )
    if (!result?.candidate || !result?.todo) {
      throw new Error('创建 Todo 的响应格式无效，请稍后重试。')
    }
    candidates.value = replaceActionCandidate(candidates.value, result.candidate)
    candidateNotices.value = {
      ...candidateNotices.value,
      [candidate.id]: 'Todo 已创建'
    }
    loaded.value = true
  } catch (error) {
    console.error(error)
    if (error.status === 409) {
      await refreshAfterConflict(error.message || '行动状态已经发生变化')
    } else {
      // 不做乐观更新；失败时原 PENDING Candidate 和操作按钮保持可见。
      errorMessage.value = error.message || '创建 Todo 失败，请稍后重试。'
    }
  } finally {
    finishCandidateOperation(candidate.id)
  }
}

const dismissCandidate = async (candidate) => {
  if (isCandidateProcessing(candidate.id) || extracting.value || props.disabled) return
  beginCandidateOperation(candidate.id, 'dismiss')
  errorMessage.value = ''
  successMessage.value = ''

  try {
    const result = await requestJson(
      `/api/inbox/${props.inboxItemId}/action-candidates/${candidate.id}/dismiss`,
      { method: 'POST' },
      '忽略行动失败，请稍后重试。'
    )
    if (!result?.id || !result?.status) {
      throw new Error('忽略行动的响应格式无效，请稍后重试。')
    }
    candidates.value = replaceActionCandidate(candidates.value, result)
    successMessage.value = '已忽略该行动。'
    loaded.value = true
  } catch (error) {
    console.error(error)
    if (error.status === 409) {
      await refreshAfterConflict(error.message || '行动状态已经发生变化')
    } else {
      // 后端成功前不移除 Candidate，避免失败后再做复杂的 UI rollback。
      errorMessage.value = error.message || '忽略行动失败，请稍后重试。'
    }
  } finally {
    finishCandidateOperation(candidate.id)
  }
}
</script>

<template>
  <section class="action-panel" aria-label="行动候选" :aria-busy="panelBusy">
    <div class="action-panel-header">
      <div>
        <h4>行动</h4>
        <span v-if="loaded" class="action-count">{{ pendingCandidates.length }} 个待处理</span>
      </div>
      <div class="action-panel-actions">
        <button
          class="action-secondary-button"
          type="button"
          :disabled="disabled || loadingCandidates || extracting || hasDecisionInProgress"
          @click="loadCandidates()"
        >
          {{ loadingCandidates ? '加载中…' : loaded ? '刷新行动' : '查看行动' }}
        </button>
        <button
          class="action-detect-button"
          type="button"
          :disabled="disabled || extracting || loadingCandidates || hasDecisionInProgress"
          @click="extractCandidates"
        >
          {{ extracting ? '正在检测…' : loaded ? '重新检测' : '检测行动' }}
        </button>
      </div>
    </div>

    <p v-if="errorMessage" class="action-error" role="alert">{{ errorMessage }}</p>
    <p v-if="successMessage" class="action-success" role="status">{{ successMessage }}</p>
    <p
      v-if="loaded && visibleCandidates.length === 0 && !loadingCandidates && !extracting"
      class="action-empty"
      role="status"
    >
      暂未检测到需要处理的行动。
    </p>

    <div v-if="visibleCandidates.length > 0" class="candidate-list" aria-live="polite">
      <article
        v-for="candidate in visibleCandidates"
        :key="candidate.id"
        class="candidate-card"
        :class="{ 'is-accepted': candidate.status === ACTION_CANDIDATE_STATUS.ACCEPTED }"
        :aria-busy="isCandidateProcessing(candidate.id)"
      >
        <div class="candidate-meta">
          <span class="candidate-type">{{ actionCandidateTypeLabel(candidate.actionType) }}</span>
          <span
            v-if="candidate.status === ACTION_CANDIDATE_STATUS.ACCEPTED"
            class="candidate-status"
          >
            已接受
          </span>
        </div>
        <h5>{{ candidate.title }}</h5>
        <p v-if="actionCandidateDeadline(candidate)" class="candidate-deadline">
          <strong>截止：</strong>{{ actionCandidateDeadline(candidate).text }}
          <span
            v-if="actionCandidateDeadline(candidate).unresolved"
            class="candidate-date-note"
          >
            日期待确认
          </span>
        </p>
        <p v-if="candidate.evidence" class="candidate-evidence" :title="candidate.evidence">
          <strong>来源：</strong>{{ candidate.evidence }}
        </p>

        <p
          v-if="candidate.status === ACTION_CANDIDATE_STATUS.ACCEPTED"
          class="candidate-accepted-message"
          role="status"
        >
          {{ candidateNotices[candidate.id] || 'Todo 已创建' }}
        </p>
        <div
          v-if="candidate.status === ACTION_CANDIDATE_STATUS.PENDING"
          class="candidate-actions"
        >
          <button
            class="candidate-accept-button"
            type="button"
            :disabled="disabled || extracting || isCandidateProcessing(candidate.id)"
            @click="acceptCandidate(candidate)"
          >
            {{ operationFor(candidate.id) === 'accept' ? '创建中…' : '创建 Todo' }}
          </button>
          <button
            class="candidate-dismiss-button"
            type="button"
            :disabled="disabled || extracting || isCandidateProcessing(candidate.id)"
            @click="dismissCandidate(candidate)"
          >
            {{ operationFor(candidate.id) === 'dismiss' ? '忽略中…' : '忽略' }}
          </button>
        </div>
      </article>
    </div>
  </section>
</template>
