<script setup>
import { computed, onMounted, ref } from 'vue'
import { completeTodo, getTodos, reopenTodo } from '../todoApi.js'
import { applyTodoOperationResult, TODO_STATUS, todoCalendarDate } from '../todoState.js'

const activeStatus = ref(TODO_STATUS.OPEN)
const todos = ref([])
const loading = ref(false)
const listErrorMessage = ref('')
const todoOperations = ref({})
const todoErrors = ref({})
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

onMounted(() => loadTodos(TODO_STATUS.OPEN))
</script>

<template>
  <section class="todo-section" aria-labelledby="todo-heading">
    <div class="section-heading todo-heading">
      <div>
        <h2 id="todo-heading">Todo</h2>
        <p>查看已确认的行动，并维护完成状态。</p>
      </div>
      <span v-if="!loading && !listErrorMessage">{{ todos.length }} 条</span>
    </div>

    <div class="todo-status-switch" role="tablist" aria-label="Todo 状态">
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
        v-for="todo in todos"
        :key="todo.id"
        class="todo-item"
        :class="{ 'is-completed': todo.status === TODO_STATUS.COMPLETED }"
        :aria-busy="isTodoProcessing(todo.id)"
      >
        <div class="todo-item-main">
          <h3>{{ todo.title }}</h3>
          <p v-if="todo.description" class="todo-description">{{ todo.description }}</p>
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
        <button
          v-if="todo.status === TODO_STATUS.OPEN"
          class="todo-complete-button"
          type="button"
          :disabled="isTodoProcessing(todo.id)"
          @click="runTodoOperation(todo, 'complete')"
        >
          {{ operationFor(todo.id) === 'complete' ? '完成中…' : '完成' }}
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
      </article>
    </div>
  </section>
</template>
