<script setup lang="ts">
import { onMounted, ref } from 'vue';
import CollapseCard from '@/components/custom/collapse-card/index.vue';
import { fetchMembershipTarget, updateMembershipTarget } from '@/service/api/home-config';
import EntryTargetEditor from './entry-target-editor.vue';
import { validateEntryTarget } from './entry-target';

const target = ref('');
const loading = ref(true);
const failed = ref(false);
const saving = ref(false);

async function load() {
  loading.value = true;
  failed.value = false;
  try {
    const { data, error } = await fetchMembershipTarget();
    if (error || !data) failed.value = true;
    else target.value = data.target;
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (saving.value || !validateEntryTarget('app', target.value)) return;
  saving.value = true;
  try {
    const { data, error } = await updateMembershipTarget(target.value.trim());
    if (!error && data) {
      target.value = data.target;
      window.$message?.success('会员入口已保存');
    }
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <CollapseCard title="会员入口设置">
    <NAlert type="info" class="mb-16px">设置「我的」页次元币右侧的会员入口打开哪个 App 页面。</NAlert>
    <NSpin :show="loading">
      <NAlert v-if="failed" type="error">会员入口配置加载失败。<NButton size="small" @click="load">重试</NButton></NAlert>
      <NForm v-else-if="!loading" :disabled="saving" label-placement="left" label-width="100">
        <EntryTargetEditor v-model:link-url="target" app-only :max-length="500" />
        <NButton type="primary" :loading="saving" @click="save">保存会员入口</NButton>
      </NForm>
    </NSpin>
  </CollapseCard>
</template>
