<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { getRelatedItems } from '../relatedItemsApi.js'
import {
  inboxItemTypeLabel,
  normalizeRelatedItems,
  relatedItemPreview,
  relatedItemTitle
} from '../relatedItemsState.js'
import { createLatestRequestGuard } from '../searchRequestGuard.js'

const props = defineProps({
  inboxItemId: {
    type: Number,
    required: true
  },
  expanded: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['toggle', 'open-inbox-item'])
const relatedItems = ref([])
const loading = ref(false)
const loaded = ref(false)
const errorMessage = ref('')
const requestGuard = createLatestRequestGuard()
let unmounted = false

const resetPanelState = () => {
  // 收起或切换条目时立即作废旧请求，旧响应不能污染下一次展开。
  requestGuard.invalidate()
  relatedItems.value = []
  loading.value = false
  loaded.value = false
  errorMessage.value = ''
}

const loadRelatedItems = async () => {
  const requestId = requestGuard.begin()
  loading.value = true
  errorMessage.value = ''

  try {
    const result = await getRelatedItems(props.inboxItemId)
    if (!requestGuard.isLatest(requestId) || unmounted || !props.expanded) return
    if (!Array.isArray(result)) {
      throw new Error('相关内容响应格式无效，请稍后重试。')
    }
    relatedItems.value = normalizeRelatedItems(result)
    loaded.value = true
  } catch (error) {
    if (!requestGuard.isLatest(requestId) || unmounted || !props.expanded) return
    console.error(error)
    relatedItems.value = []
    loaded.value = false
    errorMessage.value = error.message || '相关内容加载失败，请稍后重试。'
  } finally {
    if (requestGuard.isLatest(requestId) && !unmounted && props.expanded) {
      loading.value = false
    }
  }
}

watch(
  () => props.expanded,
  (expanded) => {
    resetPanelState()
    // 主列表只渲染折叠入口；用户明确展开单条 InboxItem 后才读取已有 Relation。
    if (expanded) loadRelatedItems()
  },
  { immediate: true }
)

const formatTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString()
}

onBeforeUnmount(() => {
  unmounted = true
  requestGuard.invalidate()
})
</script>

<template>
  <section class="related-panel" aria-label="相关内容">
    <div class="related-panel-header">
      <h4>相关内容</h4>
      <button
        class="related-toggle-button"
        type="button"
        :aria-expanded="expanded"
        :aria-controls="`related-items-${inboxItemId}`"
        @click="emit('toggle', inboxItemId)"
      >
        {{ expanded ? '收起相关内容' : '查看相关内容' }}
      </button>
    </div>

    <div v-if="expanded" :id="`related-items-${inboxItemId}`" class="related-panel-body">
      <p v-if="loading" class="related-status" role="status">正在加载相关内容…</p>
      <p v-else-if="errorMessage" class="related-error" role="alert">
        {{ errorMessage }}
        <button type="button" @click="loadRelatedItems">重新加载</button>
      </p>
      <p
        v-else-if="loaded && relatedItems.length === 0"
        class="related-empty"
        role="status"
      >
        暂未发现相关内容
      </p>

      <ul v-else-if="relatedItems.length > 0" class="related-list" aria-live="polite">
        <li v-for="item in relatedItems" :key="item.id">
          <button
            class="related-item-button"
            type="button"
            @click="emit('open-inbox-item', item)"
          >
            <span class="related-item-meta">
              <span>{{ inboxItemTypeLabel(item.type) }}</span>
              <time v-if="item.createdTime">{{ formatTime(item.createdTime) }}</time>
              <span v-if="item.favorite" aria-label="已收藏">★</span>
            </span>
            <strong>{{ relatedItemTitle(item) }}</strong>
            <span v-if="relatedItemPreview(item)" class="related-item-preview">
              {{ relatedItemPreview(item) }}
            </span>
            <span v-if="item.category" class="related-item-category">{{ item.category }}</span>
          </button>
        </li>
      </ul>
    </div>
  </section>
</template>
