<script setup>
import { onMounted, ref } from 'vue'

const title = ref('')
const content = ref('')
const sourceUrl = ref('')
const captureType = ref('TEXT')
const inboxItems = ref([])
const loading = ref(false)
const saving = ref(false)
const deletingId = ref(null)
const archivingId = ref(null)
const favoritingId = ref(null)
const errorMessage = ref('')

const loadInbox = async () => {
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

  saving.value = true
  errorMessage.value = ''
  try {
    const response = await fetch('/api/inbox', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody)
    })
    if (!response.ok) throw new Error('保存失败')

    title.value = ''
    content.value = ''
    sourceUrl.value = ''
    await loadInbox()
  } catch (error) {
    console.error(error)
    errorMessage.value = '保存失败，请稍后重试。'
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

const formatTime = (value) => value ? new Date(value).toLocaleString() : ''

onMounted(loadInbox)
</script>

<template>
  <main class="page-shell">
    <header class="page-header">
      <p class="eyebrow">Capture first, organize later</p>
      <h1>LifeInbox</h1>
      <p>先把值得保留的文字和链接放进来。</p>
    </header>

    <section class="capture-card" aria-labelledby="capture-heading">
      <h2 id="capture-heading">添加到 Inbox</h2>
      <div class="capture-type-switch" aria-label="选择内容类型">
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'TEXT' }"
          :aria-pressed="captureType === 'TEXT'"
          @click="captureType = 'TEXT'"
        >
          文字
        </button>
        <button
          class="type-button"
          type="button"
          :class="{ active: captureType === 'URL' }"
          :aria-pressed="captureType === 'URL'"
          @click="captureType = 'URL'"
        >
          链接
        </button>
      </div>

      <form @submit.prevent="saveItem">
        <label for="title">
          {{ captureType === 'URL' ? '标题（可选，将尝试自动获取）' : '标题（可选）' }}
        </label>
        <input
          id="title"
          v-model="title"
          type="text"
          maxlength="255"
          :placeholder="captureType === 'TEXT' ? '例如：学习 Agent' : '例如：Spring AI MCP'"
        />

        <template v-if="captureType === 'TEXT'">
          <label for="content">内容</label>
        <textarea id="content" v-model="content" rows="6" required
          placeholder="例如：今天准备学习 Agent Memory"></textarea>
        </template>

        <template v-else>
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

        <button type="submit" :disabled="saving">
          {{ saving ? '保存中…' : captureType === 'TEXT' ? '保存文字' : '保存链接' }}
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
      <p v-else-if="inboxItems.length === 0" class="empty-state">Inbox 还是空的，先保存一段文字吧。</p>
      <div v-else class="item-list">
        <article v-for="item in inboxItems" :key="item.id" class="inbox-item">
          <div class="item-top">
            <div class="item-meta">
              <span>{{ item.type === 'URL' ? '🔗 URL' : item.type }}</span>
              <time>{{ formatTime(item.createdTime) }}</time>
            </div>
            <div class="item-actions">
              <button
                class="favorite-button"
                type="button"
                :class="{ 'is-favorite': item.favorite === 1 }"
                :disabled="favoritingId === item.id || archivingId === item.id || deletingId === item.id"
                @click="toggleFavorite(item)"
              >
                {{ item.favorite === 1 ? '★ 已收藏' : '☆ 收藏' }}
              </button>
              <button
                class="archive-button"
                type="button"
                :disabled="archivingId === item.id || deletingId === item.id || favoritingId === item.id"
                @click="archiveItem(item.id)"
              >
                {{ archivingId === item.id ? '归档中…' : '归档' }}
              </button>
              <button
                class="delete-button"
                type="button"
                :disabled="deletingId === item.id || archivingId === item.id || favoritingId === item.id"
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
          <template v-else>
            <h3 v-if="item.title">{{ item.title }}</h3>
            <p>{{ item.content }}</p>
          </template>
        </article>
      </div>
    </section>
  </main>
</template>
