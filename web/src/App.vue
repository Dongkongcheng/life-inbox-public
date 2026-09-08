<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import ActionCandidatePanel from './components/ActionCandidatePanel.vue'
import HighlightedText from './components/HighlightedText.vue'
import RelatedItemsPanel from './components/RelatedItemsPanel.vue'
import TodoPanel from './components/TodoPanel.vue'
import { requestJson } from './apiClient.js'
import { createLatestRequestGuard } from './searchRequestGuard.js'
import { TODO_STATUS } from './todoState.js'

// Capture 表单状态由四种类型共用，切换类型时只展示该类型需要的字段。
const title = ref('')
const content = ref('')
const sourceUrl = ref('')
const selectedFile = ref(null)
const fileInput = ref(null)
const imagePreviewUrl = ref('')
const captureType = ref('TEXT')
const inboxItems = ref([])
const loading = ref(false)
const searchQuery = ref('')
const activeSearchQuery = ref('')
const searchMode = ref('keyword')
const activeSearchMode = ref('keyword')
const searchType = ref('')
const searchCategory = ref('')
const searchFavorite = ref('')
const activeSearchType = ref('')
const activeSearchCategory = ref('')
const activeSearchFavorite = ref('')
const searchErrorMessage = ref('')
const saving = ref(false)
const deletingId = ref(null)
const archivingId = ref(null)
const favoritingId = ref(null)
const analyzingId = ref(null)
const analysisErrorItemId = ref(null)
const analysisErrorMessage = ref('')
const errorMessage = ref('')
const activeView = ref('inbox')
const activeTodoStatus = ref(TODO_STATUS.OPEN)
const expandedInboxItemId = ref(null)
const expandedRelatedItemId = ref(null)

// 只筛选当前已加载的 Inbox；搜索仍沿用现有后端查询与请求保护。
const inboxCollection = ref('all')
const inboxTypeFilter = ref('')
const todoPreview = ref(null)
const contentTypeFilters = [
  { value: '', label: '全部' },
  { value: 'TEXT', label: '文字' },
  { value: 'URL', label: '链接' },
  { value: 'FILE', label: '文件' },
  { value: 'IMAGE', label: '图片' }
]
const visibleInboxItems = computed(() => activeView.value === 'inbox'
  ? inboxItems.value.filter((item) => (
    (inboxCollection.value !== 'favorites' || item.favorite === 1)
    && (!inboxTypeFilter.value || item.type === inboxTypeFilter.value)
  ))
  : inboxItems.value)
const viewLabel = computed(() => activeView.value === 'search'
  ? '智能搜索'
  : activeView.value === 'todos'
    ? activeTodoStatus.value === TODO_STATUS.OPEN ? '待完成' : '已完成'
    : inboxCollection.value === 'favorites' ? '我的收藏' : '收件箱')
const todayLabel = new Intl.DateTimeFormat('zh-CN', {
  month: 'long', day: 'numeric', weekday: 'long'
}).format(new Date())

const AI_STATUS_POLL_INTERVAL_MS = 1500
const CAPTURE_STATUS_DISCOVERY_REFRESHES = 3
let inboxPollTimer = null
let captureStatusDiscoveryRemaining = 0
let pageUnmounted = false
const inboxRequestGuard = createLatestRequestGuard()

// Entity Type 由 Analyze 契约限制为有限集合，前端只负责转换成便于阅读的中文标签。
const entityTypeLabels = {
  PERSON: '人物',
  ORGANIZATION: '组织',
  LOCATION: '地点',
  TECHNOLOGY: '技术',
  PRODUCT: '产品',
  EVENT: '事件',
  OTHER: '其他'
}

const aiStatusLabels = {
  NOT_PROCESSED: '待分析',
  PROCESSING: '分析中…',
  SUCCESS: '已分析',
  FAILED: '分析失败'
}

const searchModeLabels = {
  keyword: '关键词',
  hybrid: '混合',
  semantic: '语义'
}

const searchModeLabel = (mode) => searchModeLabels[mode] || searchModeLabels.keyword
const searchPlaceholder = (mode) => {
  if (mode === 'semantic') return '用自然语言描述你记得的内容'
  if (mode === 'hybrid') return '同时使用关键词和语义查找内容'
  return '搜索标题、内容或 AI 整理信息'
}
const searchButtonLabel = (mode) => mode === 'keyword' ? '搜索' : `${searchModeLabel(mode)}搜索`
const searchFailureMessage = (mode) => mode === 'keyword'
  ? '搜索失败，请稍后重试。'
  : `${searchModeLabel(mode)}搜索失败，请稍后重试。`
const searchEmptyMessage = (mode) => mode === 'keyword'
  ? '没有找到匹配内容。'
  : '没有找到相关内容。'

const clearSelectedUpload = () => {
  // createObjectURL 占用浏览器内存，切换类型和离开页面时都需要主动释放。
  if (imagePreviewUrl.value) {
    URL.revokeObjectURL(imagePreviewUrl.value)
    imagePreviewUrl.value = ''
  }
  selectedFile.value = null
  if (fileInput.value) fileInput.value.value = ''
}

const changeCaptureType = (type) => {
  // 文件选择器不能跨 FILE/IMAGE 复用旧选择，切换时同时清理预览和文件状态。
  if (captureType.value !== type) clearSelectedUpload()
  captureType.value = type
  errorMessage.value = ''
}

const handleFileChange = (event) => {
  if (imagePreviewUrl.value) {
    URL.revokeObjectURL(imagePreviewUrl.value)
    imagePreviewUrl.value = ''
  }
  selectedFile.value = event.target.files?.[0] || null
  if (captureType.value === 'IMAGE' && selectedFile.value) {
    // 本地预览不上传文件，只让用户在提交前确认选择是否正确。
    imagePreviewUrl.value = URL.createObjectURL(selectedFile.value)
  }
}

const clearInboxPoll = () => {
  if (inboxPollTimer !== null) {
    window.clearTimeout(inboxPollTimer)
    inboxPollTimer = null
  }
}

const scheduleInboxPollIfNeeded = () => {
  clearInboxPoll()
  if (pageUnmounted) return
  const hasFreshProcessing = inboxItems.value.some(
    (item) => item.aiStatus === 'PROCESSING' && item.aiProcessingStale !== true
  )

  if (hasFreshProcessing) {
    // 一旦看到后台任务已领取状态，就只跟随真实 PROCESSING，直到成功或失败。
    captureStatusDiscoveryRemaining = 0
  } else if (captureStatusDiscoveryRemaining > 0) {
    // Capture 提交与后台领取之间存在很短的 NOT_PROCESSED 窗口，只做有限次数发现刷新。
    captureStatusDiscoveryRemaining -= 1
  } else {
    return
  }

  inboxPollTimer = window.setTimeout(() => {
    inboxPollTimer = null
    refreshCurrentView({ background: true })
  }, AI_STATUS_POLL_INTERVAL_MS)
}

const refreshCurrentView = async ({ background = false } = {}) => {
  // 写操作和 AI 轮询都刷新当前视图，避免搜索结果被普通 Inbox 列表意外覆盖。
  clearInboxPoll()
  const requestId = inboxRequestGuard.begin()
  const searching = activeSearchQuery.value !== ''
  if (!background) {
    loading.value = true
    if (searching) searchErrorMessage.value = ''
    else errorMessage.value = ''
  }
  try {
    let endpoint = '/api/inbox'
    if (searching) {
      // 所有后台刷新和条目操作都从已执行的搜索状态生成 URL，避免丢失筛选条件。
      const params = new URLSearchParams({ q: activeSearchQuery.value })
      params.set('mode', activeSearchMode.value)
      if (activeSearchType.value) params.set('type', activeSearchType.value)
      if (activeSearchCategory.value) params.set('category', activeSearchCategory.value)
      if (activeSearchFavorite.value) params.set('favorite', activeSearchFavorite.value)
      endpoint = `/api/search?${params.toString()}`
    }
    const nextItems = await requestJson(
      endpoint,
      undefined,
      searching
        ? searchFailureMessage(activeSearchMode.value)
        : '加载 Inbox 失败，请稍后重试。'
    )
    // 搜索、清除和轮询可能并发返回；旧请求不得覆盖用户最后选择的视图。
    if (!inboxRequestGuard.isLatest(requestId) || pageUnmounted) return
    inboxItems.value = nextItems
    scheduleInboxPollIfNeeded()
  } catch (error) {
    if (!inboxRequestGuard.isLatest(requestId) || pageUnmounted) return
    console.error(error)
    if (searching) {
      if (!background) inboxItems.value = []
      searchErrorMessage.value = error.message || '搜索失败，请确认后端服务已启动。'
    } else {
      errorMessage.value = error.message || '加载 Inbox 失败，请确认后端服务已启动。'
    }
  } finally {
    // 最新请求无论是否为后台刷新，都负责结束可能由前一个前台请求开启的 Loading。
    if (inboxRequestGuard.isLatest(requestId)) loading.value = false
  }
}

const searchInbox = async () => {
  const normalizedQuery = searchQuery.value.trim()
  searchErrorMessage.value = ''
  if (!normalizedQuery) {
    searchErrorMessage.value = '请输入搜索关键词。'
    return
  }
  if (normalizedQuery.length > 200) {
    searchErrorMessage.value = '搜索关键词长度不能超过 200。'
    return
  }

  activeSearchQuery.value = normalizedQuery
  activeSearchMode.value = searchMode.value
  activeSearchType.value = searchType.value
  activeSearchCategory.value = searchCategory.value.trim()
  activeSearchFavorite.value = searchFavorite.value
  activeView.value = 'search'
  expandedInboxItemId.value = null
  expandedRelatedItemId.value = null
  await refreshCurrentView()
}

const clearSearch = async () => {
  searchQuery.value = ''
  activeSearchQuery.value = ''
  searchMode.value = 'keyword'
  activeSearchMode.value = 'keyword'
  searchType.value = ''
  searchCategory.value = ''
  searchFavorite.value = ''
  activeSearchType.value = ''
  activeSearchCategory.value = ''
  activeSearchFavorite.value = ''
  searchErrorMessage.value = ''
  expandedInboxItemId.value = null
  expandedRelatedItemId.value = null
  await refreshCurrentView()
}

const syncTodoStatus = (status) => {
  if (Object.values(TODO_STATUS).includes(status)) activeTodoStatus.value = status
}

const switchView = async (view, todoStatus = activeTodoStatus.value) => {
  if (!['inbox', 'search', 'todos'].includes(view)) return

  expandedInboxItemId.value = null
  expandedRelatedItemId.value = null
  if (view === 'todos') syncTodoStatus(todoStatus)
  activeView.value = view

  if (view === 'inbox' && activeSearchQuery.value) {
    await clearSearch()
    return
  }

  if (view === 'search') {
    await nextTick()
    document.getElementById('inbox-search')?.focus()
  }
}

const toggleInboxItemDetail = (inboxItemId) => {
  const expanding = expandedInboxItemId.value !== inboxItemId
  expandedInboxItemId.value = expanding ? inboxItemId : null
  if (!expanding || expandedRelatedItemId.value !== inboxItemId) {
    expandedRelatedItemId.value = null
  }
}

const openInboxCollection = async (collection = 'all') => {
  inboxCollection.value = collection
  inboxTypeFilter.value = ''
  await switchView('inbox')
}

const focusCapture = async () => {
  await openInboxCollection()
  await nextTick()
  document.getElementById('capture-heading')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  const inputId = captureType.value === 'TEXT' ? 'content'
    : captureType.value === 'URL' ? 'source-url' : 'file'
  document.getElementById(inputId)?.focus({ preventScroll: true })
}

const toggleRelatedItems = (inboxItemId) => {
  expandedRelatedItemId.value = expandedRelatedItemId.value === inboxItemId
    ? null
    : inboxItemId
}

const focusInboxItem = async (inboxItemId) => {
  await nextTick()
  const target = document.getElementById(`inbox-item-${inboxItemId}`)
  if (!target) return
  target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  target.focus({ preventScroll: true })
}

const openRelatedInboxItem = async (relatedItem) => {
  const inboxItemId = relatedItem?.id
  if (!Number.isInteger(inboxItemId) || inboxItemId <= 0) return

  activeView.value = 'inbox'
  // 相关内容导航要清除本地展示筛选，确保目标卡片能被渲染并获得焦点。
  inboxCollection.value = 'all'
  inboxTypeFilter.value = ''
  // 先收起 Source 面板并作废其在途请求，导航等待列表刷新期间也不会串入旧结果。
  expandedRelatedItemId.value = null
  const targetAlreadyVisible = inboxItems.value.some((item) => item.id === inboxItemId)
  if (activeSearchQuery.value) {
    // Related DTO 只提供有界预览；先回到权威 Inbox 列表，再复用现有完整卡片查看目标。
    await clearSearch()
  } else if (!targetAlreadyVisible) {
    await refreshCurrentView()
  }

  // 同一时刻只展开目标详情，A → B → C 不会形成嵌套详情栈。
  expandedInboxItemId.value = inboxItemId
  expandedRelatedItemId.value = inboxItemId
  await focusInboxItem(inboxItemId)
}

const saveItem = async () => {
  if (captureType.value === 'TEXT' && !content.value.trim()) {
    errorMessage.value = '请输入内容。'
    return
  }
  if (captureType.value === 'URL' && !sourceUrl.value.trim()) {
    errorMessage.value = '请输入 URL。'
    return
  }
  if ((captureType.value === 'FILE' || captureType.value === 'IMAGE') && !selectedFile.value) {
    errorMessage.value = captureType.value === 'IMAGE' ? '请选择图片。' : '请选择文件。'
    return
  }

  let endpoint = '/api/inbox'
  let requestOptions
  if (captureType.value === 'FILE' || captureType.value === 'IMAGE') {
    // 二进制 Capture 使用 FormData；不要手动设置 Content-Type，让浏览器生成 boundary。
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    if (title.value.trim()) formData.append('title', title.value.trim())
    endpoint = captureType.value === 'IMAGE' ? '/api/inbox/image' : '/api/inbox/file'
    requestOptions = {
      method: 'POST',
      body: formData
    }
  } else {
    // TEXT 与 URL 继续使用统一的 JSON 接口，由后端根据 type 做条件校验。
    const requestBody = captureType.value === 'TEXT'
      ? {
        type: 'TEXT',
        title: title.value.trim() || null,
        content: content.value.trim()
      }
      : {
        type: 'URL',
        title: title.value.trim() || null,
        sourceUrl: sourceUrl.value.trim()
      }
    requestOptions = {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody)
    }
  }

  saving.value = true
  errorMessage.value = ''
  try {
    const response = await fetch(endpoint, requestOptions)
    if (!response.ok) {
      let message = response.status === 413
        ? captureType.value === 'IMAGE' ? '图片大小不能超过 10MB。' : '文件大小不能超过 20MB。'
        : '保存失败，请稍后重试。'
      try {
        const problem = await response.json()
        // 框架层的 413 无法判断 FILE/IMAGE，前端保留当前 Capture 类型对应的明确提示。
        if (problem.detail && response.status !== 413) message = problem.detail
      } catch {
        // 响应不一定包含 JSON 错误正文。
      }
      throw new Error(message)
    }

    title.value = ''
    content.value = ''
    sourceUrl.value = ''
    clearSelectedUpload()
    inboxTypeFilter.value = ''
    // 自动 Analyze 在 AFTER_COMMIT 后领取任务；有限刷新用于跨过最初的 NOT_PROCESSED 窗口。
    captureStatusDiscoveryRemaining = CAPTURE_STATUS_DISCOVERY_REFRESHES
    await refreshCurrentView()
  } catch (error) {
    console.error(error)
    errorMessage.value = error.message || '保存失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}

const deleteItem = async (id) => {
  deletingId.value = id
  errorMessage.value = ''

  try {
    const response = await fetch(`/api/inbox/${id}`, {
      method: 'DELETE'
    })

    if (response.status === 404) {
      throw new Error('NOT_FOUND')
    }
    if (!response.ok) {
      throw new Error('DELETE_FAILED')
    }

    // 成功后刷新当前视图；搜索模式下删除的条目会从当前结果中消失。
    await refreshCurrentView()
  } catch (error) {
    console.error(error)
    errorMessage.value = error.message === 'NOT_FOUND'
      ? '该条信息不存在，可能已经被删除。'
      : '删除失败，请稍后重试。'
  } finally {
    deletingId.value = null
  }
}

const archiveItem = async (id) => {
  archivingId.value = id
  errorMessage.value = ''

  try {
    const response = await fetch(`/api/inbox/${id}/archive`, {
      method: 'PUT'
    })

    if (response.status === 404) {
      throw new Error('NOT_FOUND')
    }
    if (!response.ok) {
      throw new Error('ARCHIVE_FAILED')
    }

    // 普通列表和搜索都只返回 ACTIVE，归档后刷新当前视图即可自然移除条目。
    await refreshCurrentView()
  } catch (error) {
    console.error(error)
    errorMessage.value = error.message === 'NOT_FOUND'
      ? '该条信息不存在，可能已经被删除。'
      : '归档失败，请稍后重试。'
  } finally {
    archivingId.value = null
  }
}

const toggleFavorite = async (item) => {
  favoritingId.value = item.id
  errorMessage.value = ''
  const action = item.favorite === 1 ? 'unfavorite' : 'favorite'

  try {
    const response = await fetch(`/api/inbox/${item.id}/${action}`, {
      method: 'PUT'
    })

    if (response.status === 404) {
      throw new Error('NOT_FOUND')
    }
    if (!response.ok) {
      throw new Error('FAVORITE_FAILED')
    }

    // 用后端返回的最新列表刷新 favorite，避免乐观更新失败后的回滚复杂度。
    await refreshCurrentView()
  } catch (error) {
    console.error(error)
    errorMessage.value = error.message === 'NOT_FOUND'
      ? '该条信息不存在，可能已经被删除。'
      : '收藏操作失败，请稍后重试。'
  } finally {
    favoritingId.value = null
  }
}

const analyzeItem = async (item) => {
  // 前端锁改善点击体验；真正的并发保护由 Java 的数据库条件 UPDATE 保证。
  if (analyzingId.value !== null) return
  analyzingId.value = item.id
  analysisErrorItemId.value = null
  analysisErrorMessage.value = ''

  try {
    await requestJson(
      `/api/inbox/${item.id}/ai/analyze`,
      { method: 'POST' },
      'AI 分析失败，请稍后重试。'
    )

    // 成功后重新读取 Java 持久化的数据，确保五类分析结果作为一组展示。
    await refreshCurrentView()
  } catch (error) {
    console.error(error)
    // 不在前端清空旧分析结果；重新分析失败时，用户仍能查看上一次的有效结果。
    analysisErrorItemId.value = item.id
    analysisErrorMessage.value = error.message || 'AI 分析失败，请稍后重试。'
    // Java 已把最近一次尝试记为 FAILED；重新加载后展示持久化状态和安全错误摘要。
    await refreshCurrentView()
  } finally {
    analyzingId.value = null
  }
}

// 四种 InboxItem 都复用 Java 的统一 Analyze API；IMAGE 的 OCR 细节不泄漏到前端。
const isAnalyzableItem = (item) => ['TEXT', 'URL', 'FILE', 'IMAGE'].includes(item.type)
const hasTags = (item) => Array.isArray(item.tags) && item.tags.length > 0
// Tags 面向整理，Keywords 面向内容理解；两者保持独立展示，不在前端互相推导。
const hasKeywords = (item) => Array.isArray(item.keywords) && item.keywords.length > 0
const hasEntities = (item) => Array.isArray(item.entities) && item.entities.length > 0
const entityTypeLabel = (type) => entityTypeLabels[type] ?? type
const hasAnalysis = (item) => Boolean(
  item.summary || item.category || hasTags(item) || hasKeywords(item) || hasEntities(item)
)
const effectiveAiStatus = (item) => analyzingId.value === item.id
  ? 'PROCESSING'
  : item.aiStatus || 'NOT_PROCESSED'
// stale 由 Java 按统一阈值计算；Vue 只负责展示，不能自行决定是否允许接管。
const isStaleProcessing = (item) => analyzingId.value !== item.id
  && item.aiStatus === 'PROCESSING'
  && item.aiProcessingStale === true
const isFreshProcessing = (item) => effectiveAiStatus(item) === 'PROCESSING'
  && !isStaleProcessing(item)
const aiStatusLabel = (item) => isStaleProcessing(item)
  ? '可能已中断'
  : aiStatusLabels[effectiveAiStatus(item)] || '未分析'
const aiStatusClass = (item) => `ai-status-${effectiveAiStatus(item).toLowerCase()}`
const analysisButtonLabel = (item) => {
  if (isStaleProcessing(item)) return '恢复并重试'
  const status = effectiveAiStatus(item)
  if (status === 'PROCESSING') return '分析中…'
  if (status === 'FAILED') return '重试分析'
  return status === 'SUCCESS' ? '重新分析' : 'AI 分析'
}

const formatTime = (value) => value ? new Date(value).toLocaleString() : ''

onMounted(() => {
  pageUnmounted = false
  refreshCurrentView()
})
onBeforeUnmount(() => {
  pageUnmounted = true
  inboxRequestGuard.invalidate()
  clearSelectedUpload()
  clearInboxPoll()
})
</script>

<template>
  <main class="app-shell">
    <aside class="app-sidebar">
      <div>
        <div class="brand-lockup" aria-label="LifeInbox">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <path d="m4 8 3-4h10l3 4v11H4Z" />
              <path d="M4 12h5l2 3h2l2-3h5" />
            </svg>
          </span>
          <span>LifeInbox</span>
        </div>

        <button class="sidebar-capture-button" type="button" @click="focusCapture">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          收集新内容
        </button>

        <p class="sidebar-label">我的空间 / WORKSPACE</p>
        <nav class="primary-view-switch" aria-label="主要功能">
          <button
            type="button"
            :class="{ active: activeView === 'inbox' && inboxCollection === 'all' }"
            :aria-current="activeView === 'inbox' && inboxCollection === 'all' ? 'page' : undefined"
            @click="openInboxCollection()"
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M4 7.5h16v11H4z" />
              <path d="m7 7.5 1.6-3h6.8l1.6 3M8 12h8" />
            </svg>
            <span>收件箱</span>
          </button>
          <button
            type="button"
            :class="{ active: activeView === 'search' }"
            :aria-current="activeView === 'search' ? 'page' : undefined"
            @click="switchView('search')"
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="5.5" />
              <path d="m15 15 4 4" />
            </svg>
            <span>智能搜索</span>
          </button>
          <button
            type="button"
            :class="{ active: activeView === 'inbox' && inboxCollection === 'favorites' }"
            :aria-current="activeView === 'inbox' && inboxCollection === 'favorites' ? 'page' : undefined"
            @click="openInboxCollection('favorites')"
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m12 3 2.7 5.6 6.2.9-4.5 4.4 1.1 6.1-5.5-2.9L6.5 20l1.1-6.1-4.5-4.4 6.2-.9Z" /></svg>
            <span>我的收藏</span>
          </button>
          <p class="sidebar-label sidebar-action-label">行动 / ACTIONS</p>
          <button
            type="button"
            :class="{ active: activeView === 'todos' && activeTodoStatus === TODO_STATUS.OPEN }"
            :aria-current="activeView === 'todos' && activeTodoStatus === TODO_STATUS.OPEN ? 'page' : undefined"
            @click="switchView('todos', TODO_STATUS.OPEN)"
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="4" y="4" width="16" height="16" rx="4" />
              <path d="M8 9h8M8 13h5" />
            </svg>
            <span>待完成</span>
          </button>
          <button
            type="button"
            :class="{ active: activeView === 'todos' && activeTodoStatus === TODO_STATUS.COMPLETED }"
            :aria-current="activeView === 'todos' && activeTodoStatus === TODO_STATUS.COMPLETED ? 'page' : undefined"
            @click="switchView('todos', TODO_STATUS.COMPLETED)"
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="8" />
              <path d="m8.5 12 2.2 2.2 4.8-5" />
            </svg>
            <span>已完成</span>
          </button>
        </nav>
      </div>

      <div class="sidebar-note">
        <div>
          <strong>Capture first.</strong>
          <span>Organize later.</span>
        </div>
        <div class="sidebar-profile"><span class="profile-avatar" aria-hidden="true">L</span><div><strong>我的 LifeInbox</strong><span>个人信息空间</span></div></div>
      </div>
    </aside>

    <div class="workspace">
      <div class="workspace-topbar">
        <div class="workspace-breadcrumb"><span>个人空间</span><span aria-hidden="true">/</span><strong>{{ viewLabel }}</strong></div>
        <button class="workspace-search-link" type="button" @click="switchView('search')">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6" /><path d="m15 15 5 5" /></svg>
          找回一个想法
        </button>
      </div>
      <div class="workspace-content">
      <header class="page-header">
        <div>
          <p class="eyebrow">A little less chaos.</p>
          <h1>{{ activeView === 'inbox'
            ? inboxCollection === 'favorites' ? '值得，一读再读。' : '留住每一个好想法。'
            : activeView === 'search'
              ? '找回，曾经的灵光。'
              : activeTodoStatus === TODO_STATUS.OPEN ? '让好想法，发生。' : '每一步，都算数。' }}</h1>
          <p>{{ activeView === 'inbox'
            ? inboxCollection === 'favorites' ? '为那些想反复回看的内容，留一个位置。' : '先放进来，整理的事可以慢慢来。'
            : activeView === 'search'
              ? '用关键词或自然语言，找回你曾经保存的内容。'
              : '查看已经确认的行动，并维护清晰的完成状态。' }}</p>
        </div>
        <div class="workspace-date"><strong>{{ todayLabel }}</strong><span>你的灵感，正在这里生长</span></div>
      </header>

      <div class="workspace-columns" :class="{ 'has-context': activeView === 'inbox' }">
      <div class="workspace-primary">
    <section v-if="activeView === 'inbox' && inboxCollection === 'all'" class="capture-card" aria-labelledby="capture-heading">
      <div class="card-heading">
        <span class="card-heading-icon" aria-hidden="true">✧</span>
        <div>
          <h2 id="capture-heading">此刻，想留下什么？</h2>
          <p>一段文字、一个链接，或刚刚闪过的念头。</p>
        </div>
      </div>
      <div class="capture-type-switch" role="group" aria-label="选择内容类型">
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'TEXT' }"
          :aria-pressed="captureType === 'TEXT'"
          @click="changeCaptureType('TEXT')"
        >
          <span class="type-icon type-icon-text" aria-hidden="true">Aa</span>
          <span>文字</span>
        </button>
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'URL' }"
          :aria-pressed="captureType === 'URL'"
          @click="changeCaptureType('URL')"
        >
          <span class="type-icon type-icon-url" aria-hidden="true">↗</span>
          <span>链接</span>
        </button>
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'FILE' }"
          :aria-pressed="captureType === 'FILE'"
          @click="changeCaptureType('FILE')"
        >
          <span class="type-icon type-icon-file" aria-hidden="true">▤</span>
          <span>文件</span>
        </button>
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'IMAGE' }"
          :aria-pressed="captureType === 'IMAGE'"
          @click="changeCaptureType('IMAGE')"
        >
          <span class="type-icon type-icon-image" aria-hidden="true">▧</span>
          <span>图片</span>
        </button>
      </div>

      <form id="capture-form" class="capture-form" @submit.prevent="saveItem">
        <label class="visually-hidden" for="title">
          {{ captureType === 'URL'
            ? '标题（可选，将尝试自动获取）'
            : captureType === 'FILE' || captureType === 'IMAGE'
              ? '标题（可选，默认使用文件名）'
              : '标题（可选）' }}
        </label>
        <input
          id="title"
          v-model="title"
          type="text"
          maxlength="255"
          placeholder="标题（可选）"
        />

        <template v-if="captureType === 'TEXT'">
          <label class="visually-hidden" for="content">内容</label>
          <textarea id="content" v-model="content" rows="3" required
            placeholder="直接写下来，不用先想好放在哪里…"></textarea>
        </template>

        <template v-else-if="captureType === 'URL'">
          <label class="visually-hidden" for="source-url">URL</label>
          <input
            id="source-url"
            v-model="sourceUrl"
            type="url"
            maxlength="1000"
            required
            placeholder="https://example.com/article"
          />
        </template>

        <template v-else>
          <label class="upload-label" for="file">
            {{ captureType === 'IMAGE' ? '图片（最大 10MB）' : '文件（最大 20MB）' }}
          </label>
          <input
            id="file"
            ref="fileInput"
            type="file"
            :accept="captureType === 'IMAGE'
              ? '.jpg,.jpeg,.png,.webp,.gif,.bmp'
              : '.pdf,.txt,.md,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.zip'"
            required
            @change="handleFileChange"
          />
          <img
            v-if="captureType === 'IMAGE' && imagePreviewUrl"
            class="local-image-preview"
            :src="imagePreviewUrl"
            alt="待上传图片预览"
          />
        </template>

        <button class="capture-submit" type="submit" :disabled="saving">
          {{ saving
            ? '保存中…'
            : captureType === 'TEXT'
              ? '收进 Inbox'
              : captureType === 'URL'
                ? '保存链接'
                : captureType === 'FILE'
                  ? '上传文件'
                  : '上传图片' }}
          <svg v-if="!saving" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 12h16m-6-6 6 6-6 6" /></svg>
        </button>
      </form>
      <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>
    </section>

    <section
      v-if="activeView === 'inbox' || activeView === 'search'"
      class="inbox-section"
      :class="{ 'search-results-section': activeView === 'search' }"
      aria-labelledby="inbox-heading"
    >
      <form
        v-if="activeView === 'search'"
        class="search-form"
        role="search"
        @submit.prevent="searchInbox"
      >
        <div class="search-form-heading">
          <div>
            <h2>智能搜索</h2>
            <p>用关键词或自然语言找回已经保存的内容</p>
          </div>
          <span class="search-mode-indicator">{{ searchModeLabel(searchMode) }}模式</span>
        </div>
        <label class="visually-hidden" for="inbox-search">搜索 Inbox</label>
        <div class="search-controls">
          <div class="search-input-wrap">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="5.5" />
              <path d="m15 15 4 4" />
            </svg>
            <input
              id="inbox-search"
              v-model="searchQuery"
              type="search"
              maxlength="200"
              :placeholder="searchPlaceholder(searchMode)"
            />
          </div>
          <button type="submit" :disabled="loading">
            {{ searchButtonLabel(searchMode) }}
          </button>
          <button
            v-if="activeSearchQuery"
            class="clear-search-button"
            type="button"
            :disabled="loading"
            @click="clearSearch"
          >
            清除搜索
          </button>
        </div>
        <div class="search-mode-tabs" role="tablist" aria-label="搜索模式">
          <button
            type="button"
            role="tab"
            :class="{ active: searchMode === 'keyword' }"
            :aria-selected="searchMode === 'keyword'"
            @click="searchMode = 'keyword'"
          >
            关键词搜索
          </button>
          <button
            type="button"
            role="tab"
            :class="{ active: searchMode === 'semantic' }"
            :aria-selected="searchMode === 'semantic'"
            @click="searchMode = 'semantic'"
          >
            语义搜索
          </button>
          <button
            type="button"
            role="tab"
            :class="{ active: searchMode === 'hybrid' }"
            :aria-selected="searchMode === 'hybrid'"
            @click="searchMode = 'hybrid'"
          >
            混合搜索
          </button>
        </div>
        <div class="search-filter-controls">
          <label>
            类型
            <select v-model="searchType">
              <option value="">全部</option>
              <option value="TEXT">TEXT</option>
              <option value="URL">URL</option>
              <option value="FILE">FILE</option>
              <option value="IMAGE">IMAGE</option>
            </select>
          </label>
          <label>
            分类
            <input
              v-model="searchCategory"
              type="text"
              maxlength="32"
              placeholder="全部分类"
            />
          </label>
          <label>
            收藏
            <select v-model="searchFavorite">
              <option value="">全部</option>
              <option value="true">已收藏</option>
              <option value="false">未收藏</option>
            </select>
          </label>
        </div>
      </form>
      <p v-if="activeView === 'search' && searchErrorMessage" class="search-error" role="alert">
        {{ searchErrorMessage }}
      </p>
      <div v-if="activeView === 'inbox' || activeSearchQuery" class="section-heading inbox-list-heading">
        <div>
          <h2 id="inbox-heading">{{ activeView === 'search' ? '搜索结果' : inboxCollection === 'favorites' ? '我的收藏' : '最近收集' }}</h2>
        </div>
        <span>{{ activeView === 'search' ? `${inboxItems.length} 条结果` : '按收集时间排序' }}</span>
      </div>
      <div v-if="activeView === 'inbox'" class="inbox-type-filters" role="group" aria-label="筛选内容类型">
        <button
          v-for="option in contentTypeFilters"
          :key="option.value"
          type="button"
          :class="{ active: inboxTypeFilter === option.value }"
          :aria-pressed="inboxTypeFilter === option.value"
          @click="inboxTypeFilter = option.value"
        >{{ option.label }}</button>
        <span>{{ visibleInboxItems.length }} 条内容</span>
      </div>
      <p v-if="activeView === 'inbox' && inboxCollection === 'favorites' && errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>
      <p v-if="activeView === 'search' && activeSearchQuery" class="search-context">
        模式：{{ searchModeLabel(activeSearchMode) }}
        · 查询：{{ activeSearchQuery }}
        <span v-if="activeSearchType"> · 类型：{{ activeSearchType }}</span>
        <span v-if="activeSearchCategory"> · 分类：{{ activeSearchCategory }}</span>
        <span v-if="activeSearchFavorite">
          · 收藏：{{ activeSearchFavorite === 'true' ? '已收藏' : '未收藏' }}
        </span>
      </p>

      <p v-if="activeView === 'search' && !activeSearchQuery && !searchErrorMessage" class="empty-state">
        输入你记得的关键词或描述，开始查找已保存的内容。
      </p>
      <p v-else-if="loading" class="empty-state">
        {{ activeView === 'search' ? '正在搜索…' : '正在加载…' }}
      </p>
      <p v-else-if="visibleInboxItems.length === 0 && !searchErrorMessage" class="empty-state">
        {{ activeView === 'search'
          ? searchEmptyMessage(activeSearchMode)
          : inboxCollection === 'favorites' ? '还没有符合条件的收藏。点击内容旁的星标，就能把它留在这里。'
            : inboxTypeFilter ? '暂时没有这类内容，试试其他类型。' : 'Inbox 还是空的，先保存一条信息吧。' }}
      </p>
      <div
        v-else-if="visibleInboxItems.length > 0 && (activeView === 'inbox' || activeSearchQuery)"
        class="item-list"
        :class="{ 'search-result-list': activeView === 'search' }"
      >
        <article
          v-for="item in visibleInboxItems"
          :key="item.id"
          :id="`inbox-item-${item.id}`"
          class="inbox-item"
          :class="{
            'is-related-target': expandedRelatedItemId === item.id,
            'is-expanded': expandedInboxItemId === item.id
          }"
          tabindex="-1"
          :aria-busy="isFreshProcessing(item)"
        >
          <span class="list-type-icon" :class="`list-type-${String(item.type).toLowerCase()}`" aria-hidden="true">
            <svg v-if="item.type === 'TEXT'" viewBox="0 0 24 24" fill="none"><path d="M5 5h14M12 5v14M9 19h6" /></svg>
            <svg v-else-if="item.type === 'URL'" viewBox="0 0 24 24" fill="none"><path d="m10 13 4-4m-5 7-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0m0 10a4 4 0 0 0 6 0l4-4a4 4 0 0 0-6-6l-1 1" /></svg>
            <svg v-else-if="item.type === 'IMAGE'" viewBox="0 0 24 24" fill="none"><rect x="3" y="4" width="18" height="16" rx="3" /><circle cx="8" cy="9" r="1.5" /><path d="m3 17 6-5 4 3 3-3 5 5" /></svg>
            <svg v-else viewBox="0 0 24 24" fill="none"><path d="M6 3h8l4 4v14H6Z M14 3v5h4M9 12h6M9 16h6" /></svg>
          </span>
          <div class="item-top">
            <div class="item-meta">
              <span class="type-badge" :class="`type-${String(item.type).toLowerCase()}`">
                {{ item.type }}
              </span>
              <time>{{ formatTime(item.createdTime) }}</time>
              <span class="ai-status-chip" :class="aiStatusClass(item)">
                {{ aiStatusLabel(item) }}
              </span>
            </div>
            <div class="item-actions">
              <button
                class="favorite-button"
                type="button"
                :class="{ 'is-favorite': item.favorite === 1 }"
                :aria-label="`${item.favorite === 1 ? '取消收藏' : '收藏'}：${item.title || '未命名内容'}`"
                :aria-pressed="item.favorite === 1"
                :disabled="favoritingId === item.id || archivingId === item.id || deletingId === item.id || analyzingId === item.id"
                @click="toggleFavorite(item)"
              >
                <span aria-hidden="true">{{ item.favorite === 1 ? '★' : '☆' }}</span>
              </button>
              <button
                class="detail-button"
                type="button"
                :aria-expanded="expandedInboxItemId === item.id"
                :aria-controls="`inbox-detail-${item.id}`"
                @click="toggleInboxItemDetail(item.id)"
              >
                {{ expandedInboxItemId === item.id ? '收起详情' : '查看详情' }}
              </button>
            </div>
          </div>
          <h3>
            <button class="item-title-button" type="button" :aria-expanded="expandedInboxItemId === item.id" :aria-controls="`inbox-detail-${item.id}`" @click="toggleInboxItemDetail(item.id)">
            <HighlightedText
              :text="item.title || (item.type === 'FILE'
                ? '未命名文件'
                : item.type === 'IMAGE'
                  ? '未命名图片'
                  : '未命名内容')"
              :query="activeSearchQuery"
            />
            </button>
          </h3>
          <div
            v-if="!item.summary || expandedInboxItemId === item.id || item.type === 'IMAGE'"
            class="item-content-preview"
            :class="{ 'is-expanded': expandedInboxItemId === item.id }"
          >
          <template v-if="item.type === 'URL'">
            <a class="source-link" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">
              {{ item.sourceUrl }}
            </a>
          </template>
          <template v-else-if="item.type === 'FILE'">
            <div class="file-links">
              <a :href="item.fileUrl" target="_blank" rel="noopener noreferrer">查看</a>
              <a :href="item.fileUrl" :download="item.title || 'download'">下载</a>
            </div>
          </template>
          <template v-else-if="item.type === 'IMAGE'">
            <a class="image-link" :href="item.fileUrl" target="_blank" rel="noopener noreferrer">
              <img
                class="image-thumbnail"
                :src="item.fileUrl"
                :alt="item.title || 'Inbox 图片'"
                loading="lazy"
              />
            </a>
          </template>
          <template v-else>
            <p><HighlightedText :text="item.content" :query="activeSearchQuery" /></p>
          </template>
          </div>
          <section
            v-if="item.summary && expandedInboxItemId !== item.id"
            class="ai-summary-preview"
            aria-label="AI 摘要"
          >
            <strong>✦ AI 摘要</strong>
            <p><HighlightedText :text="item.summary" :query="activeSearchQuery" /></p>
          </section>
          <div
            v-if="expandedInboxItemId !== item.id && (item.category || hasTags(item))"
            class="card-taxonomy"
          >
            <span v-if="item.category" class="analysis-category">
              <HighlightedText :text="item.category" :query="activeSearchQuery" />
            </span>
            <span
              v-for="(tag, index) in (item.tags || []).slice(0, 3)"
              :key="`${item.id}-preview-tag-${index}-${tag}`"
              class="analysis-tag"
            >
              <HighlightedText :text="tag" :query="activeSearchQuery" />
            </span>
            <span v-if="(item.tags || []).length > 3" class="tag-overflow">
              +{{ item.tags.length - 3 }}
            </span>
          </div>
          <section
            v-if="expandedInboxItemId === item.id"
            :id="`inbox-detail-${item.id}`"
            class="item-detail"
            aria-label="Inbox 内容详情"
          >
            <div class="item-detail-heading">
              <div>
                <h4>分析与关联</h4>
                <p>完整原文已展开，下方是 AI 分析、行动建议与相关内容</p>
              </div>
              <div class="item-detail-actions">
                <button
                  class="archive-button"
                  type="button"
                  :disabled="archivingId === item.id || deletingId === item.id || favoritingId === item.id || analyzingId === item.id"
                  @click="archiveItem(item.id)"
                >
                  {{ archivingId === item.id ? '归档中…' : '归档' }}
                </button>
                <button
                  class="delete-button"
                  type="button"
                  :disabled="deletingId === item.id || archivingId === item.id || favoritingId === item.id || analyzingId === item.id"
                  @click="deleteItem(item.id)"
                >
                  {{ deletingId === item.id ? '删除中…' : '删除' }}
                </button>
              </div>
            </div>
          <section
            v-if="isAnalyzableItem(item) && hasAnalysis(item)"
            class="analysis-block"
            aria-label="AI 分析结果"
            aria-live="polite"
          >
            <h4>AI 分析</h4>
            <div v-if="item.summary" class="analysis-field">
              <strong class="analysis-label">摘要</strong>
              <p><HighlightedText :text="item.summary" :query="activeSearchQuery" /></p>
            </div>
            <div v-if="item.category" class="analysis-field">
              <strong class="analysis-label">分类</strong>
              <span class="analysis-category">
                <HighlightedText :text="item.category" :query="activeSearchQuery" />
              </span>
            </div>
            <div v-if="hasTags(item)" class="analysis-field">
              <strong class="analysis-label">标签</strong>
              <ul class="analysis-tags" aria-label="AI 标签">
                <li
                  v-for="(tag, index) in item.tags"
                  :key="`${item.id}-${index}-${tag}`"
                  class="analysis-tag"
                >
                  <HighlightedText :text="tag" :query="activeSearchQuery" />
                </li>
              </ul>
            </div>
            <div v-if="hasKeywords(item)" class="analysis-field">
              <strong class="analysis-label">关键词</strong>
              <ul class="analysis-keywords" aria-label="AI 关键词">
                <li
                  v-for="(keyword, index) in item.keywords"
                  :key="`${item.id}-keyword-${index}-${keyword}`"
                  class="analysis-keyword"
                >
                  <HighlightedText :text="keyword" :query="activeSearchQuery" />
                </li>
              </ul>
            </div>
            <div v-if="hasEntities(item)" class="analysis-field">
              <strong class="analysis-label">实体</strong>
              <ul class="analysis-entities" aria-label="AI 实体">
                <li
                  v-for="(entity, index) in item.entities"
                  :key="`${item.id}-entity-${index}-${entity.type}-${entity.name}`"
                  class="analysis-entity"
                >
                  <span class="analysis-entity-name">
                    <HighlightedText :text="entity.name" :query="activeSearchQuery" />
                  </span>
                  <span class="analysis-entity-type">{{ entityTypeLabel(entity.type) }}</span>
                </li>
              </ul>
            </div>
          </section>
          <p
            v-if="item.aiStatus === 'FAILED' && item.aiErrorMessage"
            class="analysis-status-error"
            role="status"
          >
            分析失败：{{ item.aiErrorMessage }}
          </p>
          <p
            v-if="item.aiStatus === 'FAILED' && hasAnalysis(item)"
            class="analysis-status-note"
          >
            正在显示上一次成功的 AI 结果。
          </p>
          <p
            v-if="isStaleProcessing(item)"
            class="analysis-status-note"
            role="status"
          >
            上一次 AI 分析可能已异常中断。可以重新尝试。
          </p>
          <div v-if="isAnalyzableItem(item)" class="analysis-actions" aria-live="polite">
            <button
              class="analysis-button"
              type="button"
              :disabled="analyzingId !== null || isFreshProcessing(item) || deletingId === item.id || archivingId === item.id || favoritingId === item.id"
              @click="analyzeItem(item)"
            >
              {{ analysisButtonLabel(item) }}
            </button>
          </div>
          <p
            v-if="isAnalyzableItem(item) && analysisErrorItemId === item.id"
            class="analysis-error"
            role="alert"
          >
            {{ analysisErrorMessage }}
          </p>
          <ActionCandidatePanel
            v-if="isAnalyzableItem(item)"
            :inbox-item-id="item.id"
            :disabled="deletingId === item.id || archivingId === item.id || favoritingId === item.id || analyzingId === item.id"
            @accepted="todoPreview?.refresh()"
          />
          <RelatedItemsPanel
            :inbox-item-id="item.id"
            :expanded="expandedRelatedItemId === item.id"
            @toggle="toggleRelatedItems"
            @open-inbox-item="openRelatedInboxItem"
          />
          </section>
        </article>
      </div>
    </section>
    <p v-if="activeView === 'inbox'" class="workspace-endnote">Everything worth keeping, in one place.</p>
    <TodoPanel
      v-if="activeView === 'todos'"
      :status="activeTodoStatus"
      @status-change="syncTodoStatus"
    />
      </div>
      <aside v-if="activeView === 'inbox'" class="context-sidebar" aria-label="待办与收集提示">
        <TodoPanel ref="todoPreview" compact :status="TODO_STATUS.OPEN" @open-all="switchView('todos', TODO_STATUS.OPEN)" />
        <div class="workspace-note">
          <span aria-hidden="true">✧</span>
          <h2>收集，是整理的开始。</h2>
          <p>摘要帮你快速理解，<br>标签帮你归类，<br>相关内容让想法再次相遇。</p>
        </div>
      </aside>
      </div>
      </div>
    </div>
  </main>
</template>
