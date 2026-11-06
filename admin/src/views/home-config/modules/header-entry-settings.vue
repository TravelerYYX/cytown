<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import CollapseCard from '@/components/custom/collapse-card/index.vue';
import ImageUpload from '@/components/image-upload/index.vue';
import { fetchHeaderEntry, updateHeaderEntry } from '@/service/api/home-config';
import EntryTargetEditor from './entry-target-editor.vue';
import { validateEntryTarget } from './entry-target';

const entry = reactive<Api.P3.HeaderEntry>({ name: '', iconUrl: '', targetType: 'link', linkUrl: '', openMode: 'inapp', showTopbar: true, enabled: false });
const loading = ref(true), failed = ref(false), saving = ref(false);
async function load() {
  loading.value = true; failed.value = false;
  try {
    const { data, error } = await fetchHeaderEntry();
    if (error || !data) failed.value = true;
    else Object.assign(entry, data);
  } catch { failed.value = true; }
  finally { loading.value = false; }
}
async function save() {
  if (saving.value || loading.value || failed.value) return;
  const name = entry.name.trim(), iconUrl = entry.iconUrl.trim(), linkUrl = entry.linkUrl.trim();
  if (entry.enabled && (!name || !iconUrl || !linkUrl)) {
    window.$message?.warning('请完整填写名称、图标和跳转地址'); return;
  }
  if (Array.from(name).length > 20) { window.$message?.warning('名称需为1-20个字符'); return; }
  if ((entry.enabled || linkUrl) && !validateEntryTarget(entry.targetType, linkUrl)) return;
  saving.value = true;
  try {
    const { data, error } = await updateHeaderEntry({ ...entry, name, iconUrl, linkUrl });
    if (!error && data) { Object.assign(entry, data); window.$message?.success('搜索左侧入口已保存'); }
  } finally { saving.value = false; }
}
onMounted(load);
</script>

<template>
  <CollapseCard title="搜索左侧入口">
    <NAlert type="info" class="mb-16px">首页搜索按钮左侧显示配置的图标，点击后按下方设置跳转。名称用于页面标题；停用后隐藏入口。</NAlert>
    <NSpin :show="loading">
      <NAlert v-if="failed" type="error">入口配置加载失败。<NButton size="small" @click="load">重试</NButton></NAlert>
      <NForm v-else-if="!loading" :disabled="saving" label-placement="left" label-width="110">
        <NFormItem label="启用入口"><NSwitch v-model:value="entry.enabled" /></NFormItem>
        <NFormItem label="名称"><NInput v-model:value="entry.name" maxlength="20" placeholder="请输入入口名称" /></NFormItem>
        <NFormItem label="图标"><ImageUpload v-model="entry.iconUrl" /></NFormItem>
        <EntryTargetEditor v-model:target-type="entry.targetType" v-model:link-url="entry.linkUrl" v-model:open-mode="entry.openMode" v-model:show-topbar="entry.showTopbar" />
        <NButton type="primary" :loading="saving" @click="save">保存搜索左侧入口</NButton>
      </NForm>
    </NSpin>
  </CollapseCard>
</template>
