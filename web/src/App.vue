<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import ActionCandidatePanel from './components/ActionCandidatePanel.vue'
import HighlightedText from './components/HighlightedText.vue'
import TodoPanel from './components/TodoPanel.vue'
import { createLatestRequestGuard } from './searchRequestGuard.js'

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
  NOT_PROCESSED: '未分析',
  PROCESSING: '分析中…',
  SUCCESS: '分析完成',
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
    const response = await fetch(endpoint)
    if (!response.ok) {
      let message = searching
        ? searchFailureMessage(activeSearchMode.value)
        : '加载 Inbox 失败，请稍后重试。'
      try {
        const problem = await response.json()
        message = problem.detail || problem.message || message
      } catch {
        // 搜索与列表错误不保证带 JSON 正文，保留安全的用户提示。
      }
      throw new Error(message)
    }
    const nextItems = await response.json()
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
  await refreshCurrentView()
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
    const response = await fetch(`/api/inbox/${item.id}/ai/analyze`, {
      method: 'POST'
    })

    if (!response.ok) {
      let message = 'AI 分析失败，请稍后重试。'
      try {
        const problem = await response.json()
        message = problem.detail || problem.message || message
      } catch {
        // 上游异常不一定包含 JSON；保留对用户安全的通用提示。
      }
      throw new Error(message)
    }

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
  <main class="page-shell">
    <header class="page-header">
      <p class="eyebrow">Capture first, organize later</p>
      <h1>LifeInbox</h1>
      <p>{{ activeView === 'inbox'
        ? '先把值得保留的文字、链接、文件和图片放进来。'
        : '查看已经确认的行动，并在完成后保留清晰状态。' }}</p>
    </header>

    <nav class="primary-view-switch" aria-label="主要功能">
      <button
        type="button"
        :class="{ active: activeView === 'inbox' }"
        :aria-current="activeView === 'inbox' ? 'page' : undefined"
        @click="activeView = 'inbox'"
      >
        Inbox
      </button>
      <button
        type="button"
        :class="{ active: activeView === 'todos' }"
        :aria-current="activeView === 'todos' ? 'page' : undefined"
        @click="activeView = 'todos'"
      >
        Todo
      </button>
    </nav>

    <section v-if="activeView === 'inbox'" class="capture-card" aria-labelledby="capture-heading">
      <h2 id="capture-heading">添加到 Inbox</h2>
      <div class="capture-type-switch" aria-label="选择内容类型">
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'TEXT' }"
          :aria-pressed="captureType === 'TEXT'"
          @click="changeCaptureType('TEXT')"
        >
          文字
        </button>
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'URL' }"
          :aria-pressed="captureType === 'URL'"
          @click="changeCaptureType('URL')"
        >
          链接
        </button>
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'FILE' }"
          :aria-pressed="captureType === 'FILE'"
          @click="changeCaptureType('FILE')"
        >
          文件
        </button>
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'IMAGE' }"
          :aria-pressed="captureType === 'IMAGE'"
          @click="changeCaptureType('IMAGE')"
        >
          图片
        </button>
      </div>

      <form @submit.prevent="saveItem">
        <label for="title">
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
          :placeholder="captureType === 'TEXT'
            ? '例如：学习 Agent'
            : captureType === 'URL'
              ? '例如：Spring AI MCP'
              : captureType === 'FILE'
                ? '例如：操作系统实验报告'
                : '例如：旅行照片'"
        />

        <template v-if="captureType === 'TEXT'">
          <label for="content">内容</label>
          <textarea id="content" v-model="content" rows="6" required
            placeholder="例如：今天准备学习 Agent Memory"></textarea>
        </template>

        <template v-else-if="captureType === 'URL'">
          <label for="source-url">URL</label>
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
          <label for="file">
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

        <button type="submit" :disabled="saving">
          {{ saving
            ? '保存中…'
            : captureType === 'TEXT'
              ? '保存文字'
              : captureType === 'URL'
                ? '保存链接'
                : captureType === 'FILE'
                  ? '上传文件'
                  : '上传图片' }}
        </button>
      </form>
      <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>
    </section>

    <section v-if="activeView === 'inbox'" class="inbox-section" aria-labelledby="inbox-heading">
      <form class="search-form" role="search" @submit.prevent="searchInbox">
        <label for="inbox-search">搜索 Inbox</label>
        <div class="search-controls">
          <input
            id="inbox-search"
            v-model="searchQuery"
            type="search"
            maxlength="200"
            :placeholder="searchPlaceholder(searchMode)"
          />
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
        <div class="search-filter-controls">
          <label>
            模式
            <select v-model="searchMode">
              <option value="keyword">关键词</option>
              <option value="hybrid">混合</option>
              <option value="semantic">语义</option>
            </select>
          </label>
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
      <p v-if="searchErrorMessage" class="search-error" role="alert">
        {{ searchErrorMessage }}
      </p>
      <div class="section-heading">
        <h2 id="inbox-heading">{{ activeSearchQuery ? '搜索结果' : 'Inbox' }}</h2>
        <span>{{ inboxItems.length }} 条</span>
      </div>
      <p v-if="activeSearchQuery" class="search-context">
        模式：{{ searchModeLabel(activeSearchMode) }}
        · 查询：{{ activeSearchQuery }}
        <span v-if="activeSearchType"> · 类型：{{ activeSearchType }}</span>
        <span v-if="activeSearchCategory"> · 分类：{{ activeSearchCategory }}</span>
        <span v-if="activeSearchFavorite">
          · 收藏：{{ activeSearchFavorite === 'true' ? '已收藏' : '未收藏' }}
        </span>
      </p>

      <p v-if="loading" class="empty-state">
        {{ activeSearchQuery ? '正在搜索…' : '正在加载…' }}
      </p>
      <p v-else-if="inboxItems.length === 0 && !searchErrorMessage" class="empty-state">
        {{ activeSearchQuery
          ? searchEmptyMessage(activeSearchMode)
          : 'Inbox 还是空的，先保存一条信息吧。' }}
      </p>
      <div v-else-if="inboxItems.length > 0" class="item-list">
        <article
          v-for="item in inboxItems"
          :key="item.id"
          class="inbox-item"
          :aria-busy="isFreshProcessing(item)"
        >
          <div class="item-top">
            <div class="item-meta">
              <span>{{ item.type === 'URL'
                ? '🔗 URL'
                : item.type === 'FILE'
                  ? '📄 FILE'
                  : item.type === 'IMAGE'
                    ? '🖼️ IMAGE'
                    : item.type }}</span>
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
                :disabled="favoritingId === item.id || archivingId === item.id || deletingId === item.id || analyzingId === item.id"
                @click="toggleFavorite(item)"
              >
                {{ item.favorite === 1 ? '★ 已收藏' : '☆ 收藏' }}
              </button>
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
          <template v-if="item.type === 'URL'">
            <h3 v-if="item.title">
              <HighlightedText :text="item.title" :query="activeSearchQuery" />
            </h3>
            <a class="source-link" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">
              {{ item.sourceUrl }}
            </a>
          </template>
          <template v-else-if="item.type === 'FILE'">
            <h3>
              📄 <HighlightedText :text="item.title || '未命名文件'" :query="activeSearchQuery" />
            </h3>
            <div class="file-links">
              <a :href="item.fileUrl" target="_blank" rel="noopener noreferrer">查看</a>
              <a :href="item.fileUrl" :download="item.title || 'download'">下载</a>
            </div>
          </template>
          <template v-else-if="item.type === 'IMAGE'">
            <h3 v-if="item.title">
              <HighlightedText :text="item.title" :query="activeSearchQuery" />
            </h3>
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
            <h3 v-if="item.title">
              <HighlightedText :text="item.title" :query="activeSearchQuery" />
            </h3>
            <p><HighlightedText :text="item.content" :query="activeSearchQuery" /></p>
          </template>
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
          />
        </article>
      </div>
    </section>
    <TodoPanel v-if="activeView === 'todos'" />
  </main>
</template>
