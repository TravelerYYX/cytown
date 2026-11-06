<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { fetchAppUserDetail, fetchAppUserList } from '@/service/api';
const props = defineProps<{ value: (string | number)[]; disabled?: boolean }>();
const emit = defineEmits<{ 'update:value': [value: string[]] }>();
const options = ref<{ label: string; value: string }[]>([]);
const loading = ref(false);
let revision = 0;
async function search(keyword = '') {
  const current = ++revision;
  loading.value = true;
  try {
    const r = await fetchAppUserList({ pageNum: 1, pageSize: 50, keyword });
    if (current !== revision || !r.data) return;
    const retained = options.value.filter(x => props.value.map(String).includes(x.value));
    const next = r.data.records.map(x => ({ value: String(x.id), label: `${x.nickname} (UID: ${x.publicUid || x.id})` }));
    options.value = [...new Map([...retained, ...next].map(x => [x.value, x])).values()];
  } finally { if (current === revision) loading.value = false; }
}
watch(() => props.value, async ids => {
  for (const id of ids) {
    if (options.value.some(x => x.value === String(id))) continue;
    const r = await fetchAppUserDetail(String(id));
    if (!props.value.map(String).includes(String(id))) continue;
    options.value.push({ value: String(id), label: r.data ? `${r.data.nickname} (UID: ${r.data.publicUid || id})` : `用户 #${id}` });
  }
}, { immediate: true });
onMounted(() => search());
</script>
<template>
  <NSelect :value="value.map(String)" :disabled="disabled" :options="options" :loading="loading" multiple filterable remote clearable :max="1000" placeholder="搜索昵称、UID或账号，多选接收用户" @search="search" @update:value="emit('update:value', $event)" />
</template>
