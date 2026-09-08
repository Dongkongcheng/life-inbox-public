<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { completeTodo, getTodoSource, getTodos, reopenTodo } from '../todoApi.js'
import {
  applyTodoOperationResult,
  candidateCalendarDate,
  hasTodoSourceReference,
  TODO_STATUS,
  todoCalendarDate
} from '../todoState.js'

const props = defineProps({
  status: {
    type: String,
    default: TODO_STATUS.OPEN,
    validator: (value) => Object.values(TODO_STATUS).includes(value)
  },
  compact: {
    type: Boolean,
    default: false
  }
})
const emit = defineEmits(['status-change', 'open-all'])

const activeStatus = ref(props.status)
const todos = ref([])
const loading = ref(false)
const listErrorMessage = ref('')
const todoOperations = ref({})
const todoErrors = ref({})
const todoSources = ref({})
let listRequestId = 0

const emptyMessage = computed(() => activeStatus.value === TODO_STATUS.OPEN
  ? '暂无待办'
  : '暂无已完成 Todo')

const operationFor = (todoId) => todoOperations.value[todoId]
const isTodoProcessing = (todoId) => Boolean(operationFor(todoId))
const beginTodoOperation = (todoId, operation) => {
  todoOperations.value = { ...todoOperations.value, [todoId]: operation }
  const nextErrors = { ...todoErrors.value }
  delete nextErrors[todoId]
  todoErrors.value = nextErrors
}
const finishTodoOperation = (todoId) => {
  const next = { ...todoOperations.value }
  delete next[todoId]
  todoOperations.value = next
}
const setTodoError = (todoId, message) => {
  todoErrors.value = { ...todoErrors.value, [todoId]: message }
}

const sourceFor = (todoId) => todoSources.value[todoId] || {
  expanded: false,
  loading: false,
  loaded: false,
  data: null,
  error: ''
}
const updateSource = (todoId, changes) => {
  todoSources.value = {
    ...todoSources.value,
    [todoId]: { ...sourceFor(todoId), ...changes }
  }
}

const loadTodoSource = async (todoId) => {
  const current = sourceFor(todoId)
  if (current.loading || current.loaded) return
  updateSource(todoId, { loading: true, error: '' })
  try {
    const source = await getTodoSource(todoId)
    // 来源响应只更新当前 Todo；失败或慢请求不会阻塞 Todo 列表与其他卡片。
    updateSource(todoId, { loading: false, loaded: true, data: source, error: '' })
  } catch (error) {
    console.error(error)
    updateSource(todoId, {
      loading: false,
      loaded: false,
      data: null,
      error: error.message || 'Todo 来源加载失败，请稍后重试。'
    })
  }
}

const toggleTodoSource = (todo) => {
  const current = sourceFor(todo.id)
  const expanded = !current.expanded
  updateSource(todo.id, { expanded })
  if (expanded) loadTodoSource(todo.id)
}

const retryTodoSource = (todoId) => {
  updateSource(todoId, { expanded: true, loaded: false, error: '' })
  loadTodoSource(todoId)
}

const loadTodos = async (status = activeStatus.value) => {
  const requestId = ++listRequestId
  activeStatus.value = status
  loading.value = true
  listErrorMessage.value = ''
  try {
    const result = await getTodos(status)
    if (requestId !== listRequestId) return
    todos.value = Array.isArray(result) ? result : []
  } catch (error) {
    if (requestId !== listRequestId) return
    console.error(error)
    todos.value = []
    listErrorMessage.value = error.message || 'Todo 列表加载失败，请确认后端服务已启动。'
  } finally {
    if (requestId === listRequestId) loading.value = false
  }
}

const switchStatus = (status) => {
  if (status === activeStatus.value && !listErrorMessage.value) return
  emit('status-change', status)
  loadTodos(status)
}

const runTodoOperation = async (todo, operation) => {
  if (isTodoProcessing(todo.id)) return
  const originStatus = activeStatus.value
  beginTodoOperation(todo.id, operation)
  try {
    // 不做乐观更新：只有后端返回权威 Todo 后，才更新当前列表。
    const updated = operation === 'complete'
      ? await completeTodo(todo.id)
      : await reopenTodo(todo.id)
    if (activeStatus.value === originStatus) {
      todos.value = applyTodoOperationResult(todos.value, updated, activeStatus.value)
    } else {
      await loadTodos(activeStatus.value)
    }
  } catch (error) {
    console.error(error)
    setTodoError(
      todo.id,
      error.message || (operation === 'complete'
        ? 'Todo 完成失败，请稍后重试。'
        : 'Todo 重新打开失败，请稍后重试。')
    )
  } finally {
    finishTodoOperation(todo.id)
  }
}

const formatCompletedTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

const formatSourceCreatedTime = (value) => formatCompletedTime(value) || '时间未知'

defineExpose({ refresh: () => loadTodos(props.status) })

watch(
  () => props.status,
  (status) => {
    if (status !== activeStatus.value) loadTodos(status)
  }
)

onMounted(() => loadTodos(props.status))
</script>

<template>
  <section
    class="todo-section"
    :class="{ 'todo-compact': compact }"
    :aria-labelledby="compact ? 'todo-preview-heading' : 'todo-heading'"
  >
    <div class="section-heading todo-heading">
      <div>
        <h2 :id="compact ? 'todo-preview-heading' : 'todo-heading'">
          {{ compact ? '让想法，向前一步' : 'Todo' }}
        </h2>
        <p>{{ compact ? '从收集的信息里，走向下一件事。' : '已确认的行动' }}</p>
      </div>
      <span v-if="!loading && !listErrorMessage">{{ todos.length }} 条</span>
    </div>

    <div v-if="!compact" class="todo-status-switch" role="tablist" aria-label="Todo 状态">
      <button
        type="button"
        role="tab"
        :class="{ active: activeStatus === TODO_STATUS.OPEN }"
        :aria-selected="activeStatus === TODO_STATUS.OPEN"
        @click="switchStatus(TODO_STATUS.OPEN)"
      >
        待完成
      </button>
      <button
        type="button"
        role="tab"
        :class="{ active: activeStatus === TODO_STATUS.COMPLETED }"
        :aria-selected="activeStatus === TODO_STATUS.COMPLETED"
        @click="switchStatus(TODO_STATUS.COMPLETED)"
      >
        已完成
      </button>
    </div>

    <p v-if="loading" class="empty-state" role="status">正在加载 Todo…</p>
    <p v-else-if="listErrorMessage" class="todo-list-error" role="alert">
      {{ listErrorMessage }}
      <button type="button" @click="loadTodos(activeStatus)">重试</button>
    </p>
    <p v-else-if="todos.length === 0" class="empty-state" role="status">
      {{ emptyMessage }}
    </p>

    <div v-else class="todo-list" aria-live="polite">
      <article
        v-for="todo in (compact ? todos.slice(0, 3) : todos)"
        :key="todo.id"
        class="todo-item"
        :class="{ 'is-completed': todo.status === TODO_STATUS.COMPLETED }"
        :aria-busy="isTodoProcessing(todo.id)"
      >
        <div class="todo-item-row">
          <div class="todo-item-main">
            <h3>{{ todo.title }}</h3>
            <p v-if="!compact && todo.description" class="todo-description">{{ todo.description }}</p>
            <p v-if="todoCalendarDate(todo)" class="todo-date">
              截止：<time>{{ todoCalendarDate(todo) }}</time>
            </p>
            <p v-if="todo.status === TODO_STATUS.COMPLETED" class="todo-completed-time">
              完成于：{{ formatCompletedTime(todo.completedTime) || '时间未知' }}
            </p>
            <p v-if="todoErrors[todo.id]" class="todo-operation-error" role="alert">
              {{ todoErrors[todo.id] }}
            </p>
          </div>
          <div class="todo-item-actions">
            <button
              v-if="hasTodoSourceReference(todo)"
              class="todo-source-button"
              type="button"
              :aria-expanded="sourceFor(todo.id).expanded"
              :aria-controls="`${compact ? 'todo-preview-source' : 'todo-source'}-${todo.id}`"
              @click="toggleTodoSource(todo)"
            >
              {{ sourceFor(todo.id).expanded ? '收起来源' : '查看来源' }}
            </button>
            <span v-else class="todo-no-source">无来源</span>
            <button
              v-if="todo.status === TODO_STATUS.OPEN"
              class="todo-complete-button"
              type="button"
              :aria-label="compact
                ? `${operationFor(todo.id) === 'complete' ? '正在完成' : '完成待办'}：${todo.title}`
                : undefined"
              :disabled="isTodoProcessing(todo.id)"
              @click="runTodoOperation(todo, 'complete')"
            >
              <svg
                v-if="compact"
                class="todo-complete-icon"
                viewBox="0 0 20 20"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                aria-hidden="true"
              >
                <rect x="3" y="3" width="14" height="14" rx="4" />
              </svg>
              <span v-else>{{ operationFor(todo.id) === 'complete' ? '完成中…' : '完成' }}</span>
            </button>
            <button
              v-else
              class="todo-reopen-button"
              type="button"
              :disabled="isTodoProcessing(todo.id)"
              @click="runTodoOperation(todo, 'reopen')"
            >
              {{ operationFor(todo.id) === 'reopen' ? '重新打开中…' : '重新打开' }}
            </button>
          </div>
        </div>

        <section
          v-if="sourceFor(todo.id).expanded"
          :id="`${compact ? 'todo-preview-source' : 'todo-source'}-${todo.id}`"
          class="todo-source-panel"
          aria-label="Todo 来源"
        >
          <p v-if="sourceFor(todo.id).loading" class="todo-source-status" role="status">
            正在加载来源…
          </p>
          <p v-else-if="sourceFor(todo.id).error" class="todo-source-error" role="alert">
            {{ sourceFor(todo.id).error }}
            <button type="button" @click="retryTodoSource(todo.id)">重试</button>
          </p>
          <p
            v-else-if="sourceFor(todo.id).loaded && !sourceFor(todo.id).data?.sourceAvailable"
            class="todo-source-unavailable"
          >
            原始来源已不可用，Todo 仍可正常使用。
          </p>
          <template v-else-if="sourceFor(todo.id).data?.sourceAvailable">
            <div v-if="sourceFor(todo.id).data.inboxItem" class="todo-source-block">
              <div class="todo-source-heading">
                <strong>原始 Inbox</strong>
                <span>
                  {{ sourceFor(todo.id).data.inboxItem.type || 'UNKNOWN' }}
                  · {{ sourceFor(todo.id).data.inboxItem.status || '状态未知' }}
                </span>
              </div>
              <p v-if="sourceFor(todo.id).data.inboxItem.title" class="todo-source-title">
                {{ sourceFor(todo.id).data.inboxItem.title }}
              </p>
              <p v-if="sourceFor(todo.id).data.inboxItem.preview" class="todo-source-preview">
                {{ sourceFor(todo.id).data.inboxItem.preview }}
              </p>
              <p class="todo-source-meta">
                Inbox #{{ sourceFor(todo.id).data.inboxItem.id }}
                · 保存于 {{ formatSourceCreatedTime(sourceFor(todo.id).data.inboxItem.createdTime) }}
              </p>
              <div class="todo-source-links">
                <a
                  v-if="sourceFor(todo.id).data.inboxItem.sourceUrl"
                  :href="sourceFor(todo.id).data.inboxItem.sourceUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                >打开原链接</a>
                <a
                  v-if="sourceFor(todo.id).data.inboxItem.fileUrl"
                  :href="sourceFor(todo.id).data.inboxItem.fileUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                >查看原文件</a>
              </div>
            </div>

            <div v-if="sourceFor(todo.id).data.actionCandidate" class="todo-source-block">
              <div class="todo-source-heading">
                <strong>Action Candidate</strong>
                <span>
                  {{ sourceFor(todo.id).data.actionCandidate.actionType || '类型未知' }}
                  · {{ sourceFor(todo.id).data.actionCandidate.status || '状态未知' }}
                </span>
              </div>
              <p class="todo-source-title">{{ sourceFor(todo.id).data.actionCandidate.title }}</p>
              <p class="todo-source-deadline">
                日期原文：{{ sourceFor(todo.id).data.actionCandidate.deadlineText || '无' }}
              </p>
              <p class="todo-source-deadline">
                归一化日期：
                <time v-if="candidateCalendarDate(sourceFor(todo.id).data.actionCandidate)">
                  {{ candidateCalendarDate(sourceFor(todo.id).data.actionCandidate) }}
                </time>
                <span v-else>未确定</span>
              </p>
              <p v-if="sourceFor(todo.id).data.actionCandidate.evidence" class="todo-source-evidence">
                依据：{{ sourceFor(todo.id).data.actionCandidate.evidence }}
              </p>
            </div>
          </template>
        </section>
      </article>
    </div>

    <button
      v-if="compact"
      type="button"
      class="todo-open-all-button"
      @click="emit('open-all')"
    >
      查看全部待办 →
    </button>
  </section>
</template>
