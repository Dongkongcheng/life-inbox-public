<script setup>
import { computed } from 'vue'
import { segmentHighlightText } from '../searchHighlight.js'

const props = defineProps({
  text: {
    type: [String, Number],
    default: ''
  },
  query: {
    type: String,
    default: ''
  }
})

const segments = computed(() => segmentHighlightText(props.text, props.query))
</script>

<template>
  <template v-for="(segment, index) in segments" :key="`${index}-${segment.text}`">
    <mark v-if="segment.matched" class="search-highlight">{{ segment.text }}</mark>
    <template v-else>{{ segment.text }}</template>
  </template>
</template>
