<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

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
const saving = ref(false)
const deletingId = ref(null)
const archivingId = ref(null)
const favoritingId = ref(null)
const analyzingId = ref(null)
const analysisErrorItemId = ref(null)
const analysisErrorMessage = ref('')
const errorMessage = ref('')

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

const loadInbox = async () => {
  // 所有写操作成功后都重新查询一次，避免前端自行拼装状态而与后端不一致。
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await fetch('/api/inbox')
    if (!response.ok) throw new Error('加载 Inbox 失败')
    inboxItems.value = await response.json()
  } catch (error) {
    console.error(error)
    errorMessage.value = '加载 Inbox 失败，请确认后端服务已启动。'
  } finally {
    loading.value = false
  }
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
    await loadInbox()
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

    // 成功后统一重新加载，确保页面状态与数据库结果一致。
    await loadInbox()
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

    // GET 只返回 ACTIVE，刷新后已归档条目会自然从主 Inbox 消失。
    await loadInbox()
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
    await loadInbox()
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
  // 当前没有后台任务状态，同一时间只允许一次显式分析请求，避免重复消耗模型额度。
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

    // 成功后重新读取 Java 持久化的数据，确保摘要、分类和标签作为一组展示。
    await loadInbox()
  } catch (error) {
    console.error(error)
    // 不在前端清空旧分析结果；重新分析失败时，用户仍能查看上一次的有效结果。
    analysisErrorItemId.value = item.id
    analysisErrorMessage.value = error.message || 'AI 分析失败，请稍后重试。'
  } finally {
    analyzingId.value = null
  }
}

const hasTags = (item) => Array.isArray(item.tags) && item.tags.length > 0
const hasAnalysis = (item) => Boolean(item.summary || item.category || hasTags(item))

const formatTime = (value) => value ? new Date(value).toLocaleString() : ''

onMounted(loadInbox)
onBeforeUnmount(clearSelectedUpload)
</script>

<template>
  <main class="page-shell">
    <header class="page-header">
      <p class="eyebrow">Capture first, organize later</p>
      <h1>LifeInbox</h1>
      <p>先把值得保留的文字、链接、文件和图片放进来。</p>
    </header>

    <section class="capture-card" aria-labelledby="capture-heading">
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

    <section class="inbox-section" aria-labelledby="inbox-heading">
      <div class="section-heading">
        <h2 id="inbox-heading">Inbox</h2>
        <span>{{ inboxItems.length }} 条</span>
      </div>

      <p v-if="loading" class="empty-state">正在加载…</p>
      <p v-else-if="inboxItems.length === 0" class="empty-state">Inbox 还是空的，先保存一条信息吧。</p>
      <div v-else class="item-list">
        <article
          v-for="item in inboxItems"
          :key="item.id"
          class="inbox-item"
          :aria-busy="analyzingId === item.id"
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
            <h3 v-if="item.title">{{ item.title }}</h3>
            <a class="source-link" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">
              {{ item.sourceUrl }}
            </a>
          </template>
          <template v-else-if="item.type === 'FILE'">
            <h3>📄 {{ item.title || '未命名文件' }}</h3>
            <div class="file-links">
              <a :href="item.fileUrl" target="_blank" rel="noopener noreferrer">查看</a>
              <a :href="item.fileUrl" :download="item.title || 'download'">下载</a>
            </div>
          </template>
          <template v-else-if="item.type === 'IMAGE'">
            <h3 v-if="item.title">{{ item.title }}</h3>
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
            <h3 v-if="item.title">{{ item.title }}</h3>
            <p>{{ item.content }}</p>
            <section
              v-if="item.type === 'TEXT' && hasAnalysis(item)"
              class="analysis-block"
              aria-label="AI 分析结果"
              aria-live="polite"
            >
              <h4>AI 分析</h4>
              <div v-if="item.summary" class="analysis-field">
                <strong class="analysis-label">摘要</strong>
                <p>{{ item.summary }}</p>
              </div>
              <div v-if="item.category" class="analysis-field">
                <strong class="analysis-label">分类</strong>
                <span class="analysis-category">{{ item.category }}</span>
              </div>
              <div v-if="hasTags(item)" class="analysis-field">
                <strong class="analysis-label">标签</strong>
                <ul class="analysis-tags" aria-label="AI 标签">
                  <li
                    v-for="(tag, index) in item.tags"
                    :key="`${item.id}-${index}-${tag}`"
                    class="analysis-tag"
                  >
                    {{ tag }}
                  </li>
                </ul>
              </div>
            </section>
            <div v-if="item.type === 'TEXT'" class="analysis-actions" aria-live="polite">
              <button
                class="analysis-button"
                type="button"
                :disabled="analyzingId !== null || deletingId === item.id || archivingId === item.id || favoritingId === item.id"
                @click="analyzeItem(item)"
              >
                {{ analyzingId === item.id
                  ? '分析中…'
                  : hasAnalysis(item)
                    ? '重新分析'
                    : 'AI 分析' }}
              </button>
            </div>
            <p
              v-if="analysisErrorItemId === item.id"
              class="analysis-error"
              role="alert"
            >
              {{ analysisErrorMessage }}
            </p>
          </template>
        </article>
      </div>
    </section>
  </main>
</template>
