<script setup>
import { ref, onMounted } from 'vue'

const message = ref('正在连接 LifeInbox 后端...')

const getHello = async () => {
  try {
    const response = await fetch('/api/inbox/hello')
    message.value = await response.text()
  } catch (error) {
    console.error(error)
    message.value = '连接后端失败'
  }
}

onMounted(() => {
  getHello()
})
</script>

<template>
  <div class="container">
    <h1>📥 LifeInbox</h1>

    <p>{{ message }}</p>
  </div>
</template>

<style scoped>
.container {
  max-width: 800px;
  margin: 100px auto;
  text-align: center;
  font-family: Arial, sans-serif;
}

h1 {
  font-size: 40px;
}

p {
  margin-top: 30px;
  font-size: 22px;
}
</style>