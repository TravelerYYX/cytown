<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import type { ChatAsset } from '@/service/api/chat-admin';
import { request } from '@/service/request';
import { getAuthorization } from '@/service/request/shared';
import { getBackendBaseURL } from '@/utils/service';
const props = defineProps<{ asset: ChatAsset; kind: string }>();
const poster = ref(''), source = ref(''), error = ref(''), busy = ref(false), element = ref<HTMLElement | null>(null);
const abort = new AbortController();
let observer: IntersectionObserver | null = null;
const urls: string[] = [];
async function media(id: string) {
  const response = await fetch(`${getBackendBaseURL()}/chat-admin/assets/${id}/content`, { headers: { Authorization: getAuthorization() || '' }, signal: abort.signal, cache: 'no-store' });
  if (!response.ok || response.headers.get('content-type')?.includes('json')) throw new Error('媒体读取失败，请刷新登录或重试');
  const blob = await response.blob();
  if (abort.signal.aborted) throw new Error('加载已取消');
  const url = URL.createObjectURL(blob); urls.push(url); return url;
}
async function loadPoster() { if (!props.asset.coverId) return; try { poster.value = await media(props.asset.coverId); } catch (e) { if (!abort.signal.aborted) error.value = e instanceof Error ? e.message : '封面读取失败'; } }
async function open() {
  if (source.value || busy.value) return; busy.value = true; error.value = '';
  try {
    // Check administrator/session and resource visibility before downloading the selected media.
    const result = await request<ChatAsset>({ url: `/chat-admin/assets/${props.asset.id}` });
    if (result.error || !result.data) throw new Error('媒体已删除或当前无权查看');
    source.value = await media(props.asset.id);
  } catch (e) { if (!abort.signal.aborted) error.value = e instanceof Error ? e.message : '媒体加载失败'; }
  finally { busy.value = false; }
}
onMounted(() => { if (props.kind !== 'video') return; observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) { observer?.disconnect(); void loadPoster(); } }); if (element.value) observer.observe(element.value); });
onBeforeUnmount(() => { abort.abort(); observer?.disconnect(); for (const url of urls) URL.revokeObjectURL(url); });
</script>
<template>
  <div ref="element" class="chat-admin-media">
    <video v-if="kind === 'video' && source" :src="source" :poster="poster" controls autoplay preload="metadata" />
    <NImage v-else-if="source" :src="source" width="220" object-fit="contain" />
    <div v-else>
      <img v-if="poster" :src="poster" alt="视频封面" />
      <NButton :loading="busy" @click="open">{{ kind === 'video' ? '播放视频' : kind === 'emoji' ? '查看表情' : '查看图片' }}</NButton>
    </div>
    <small v-if="error" class="text-error">{{ error }}</small>
  </div>
</template>
<style scoped>
.chat-admin-media{max-width:360px}.chat-admin-media img,.chat-admin-media video{display:block;max-width:100%;max-height:280px;margin-bottom:8px}.chat-admin-media small{display:block;margin-top:8px}
</style>
