<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { fetchPublishHealth, rebuildPublishFeed } from '@/service/api';

defineOptions({
  name: 'ServerStatus'
});

const health = ref<Api.Publish.Health>();

async function load() {
  const r = await fetchPublishHealth();
  health.value = r.data;
}

async function rebuildFeed() {
  const r = await rebuildPublishFeed();
  if (!r.error) window.$message?.success('已重建 ' + (r.data || 0) + ' 条发布 Feed');
}

onMounted(load);
</script>

<template>
  <NCard title="运行状态" :bordered="false" class="card-wrapper">
    <template #header-extra>
      <NButton @click="rebuildFeed">重建 Redis Feed</NButton>
    </template>
    <NDescriptions v-if="health" bordered :column="1">
      <NDescriptionsItem label="Redis">
        <NTag :type="health.redis.available ? 'success' : 'error'">{{ health.redis.message }}</NTag>
      </NDescriptionsItem>
      <NDescriptionsItem label="FFmpeg">
        <NTag :type="health.ffmpeg.available ? 'success' : 'error'">{{ health.ffmpeg.message }}</NTag>
        <div>{{ health.ffmpeg.ffmpegPath || '未配置 FFMPEG_PATH' }}</div>
      </NDescriptionsItem>
    </NDescriptions>
  </NCard>
</template>

<style scoped></style>
