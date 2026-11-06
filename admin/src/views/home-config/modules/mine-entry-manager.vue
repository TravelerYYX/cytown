<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import CollapseCard from '@/components/custom/collapse-card/index.vue';
import DragSortList from '@/components/custom/drag-sort-list/index.vue';
import { createMineEntry, deleteMineEntry, fetchMineEntries, saveMineSvgCode, sortMineEntries, updateMineEntry, uploadMineSvg } from '@/service/api/mine-entry';
import { mediaUrl } from '@/utils/service';
import EntryTargetEditor from './entry-target-editor.vue';
import { validateEntryTarget } from './entry-target';

const entries = ref<Api.P3.MineEntry[]>([]);
const loading = ref(false), loadError = ref(false), opened = ref(false), saving = ref(false), uploading = ref(false), changing = ref(false);
const svgMode = ref('file'), svgCode = ref(''), appliedCode = ref('');
const fileInput = ref<HTMLInputElement>();
const form = reactive<Partial<Api.P3.MineEntry>>({});
const iconPending = () => svgMode.value === 'code' && svgCode.value !== appliedCode.value;

async function load() {
  loading.value = true; loadError.value = false;
  try { const result = await fetchMineEntries(); if (result.error || !result.data) loadError.value = true; else entries.value = result.data; }
  catch { loadError.value = true; }
  finally { loading.value = false; }
}
function open(row?: Api.P3.MineEntry) {
  Object.assign(form, { id: undefined, title: '', description: '', iconUrl: '', targetType: 'link', linkUrl: '', openMode: 'inapp', showTopbar: true, sortOrder: entries.value.length ? Math.max(...entries.value.map(e => e.sortOrder)) + 1 : 0, enabled: true }, row);
  svgMode.value = 'file'; svgCode.value = ''; appliedCode.value = ''; opened.value = true;
}
async function selected(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0]; input.value = '';
  if (!file) return;
  if (!file.name.toLowerCase().endsWith('.svg') || file.size > 256 * 1024) { window.$message?.warning('请选择不超过256KB的 .svg 文件'); return; }
  uploading.value = true;
  try { const result = await uploadMineSvg(file); if (!result.error && result.data) { form.iconUrl = result.data.url; svgCode.value = ''; appliedCode.value = ''; } }
  finally { uploading.value = false; }
}
async function applyCode() {
  if (!svgCode.value.trim()) { window.$message?.warning('请粘贴 SVG 代码'); return; }
  if (new Blob([svgCode.value]).size > 256 * 1024) { window.$message?.warning('SVG 内容不能超过256KB'); return; }
  const submitted = svgCode.value;
  uploading.value = true;
  try { const result = await saveMineSvgCode(submitted); if (!result.error && result.data) { form.iconUrl = result.data.url; appliedCode.value = submitted; } }
  finally { uploading.value = false; }
}
async function save() {
  if (saving.value || uploading.value) return;
  const title = form.title?.trim() || '', description = form.description?.trim() || '', linkUrl = form.linkUrl?.trim() || '';
  if (!title || Array.from(title).length > 20 || !description || Array.from(description).length > 100 || !form.iconUrl || !linkUrl) {
    window.$message?.warning('请完整填写标题（1-20字）、介绍（1-100字）、SVG图标和跳转地址'); return;
  }
  if (iconPending()) { window.$message?.warning('请先点击“应用 SVG 代码”，确认最新图标'); return; }
  if (!validateEntryTarget(form.targetType, linkUrl)) return;
  saving.value = true;
  try {
    const result = await (form.id ? updateMineEntry : createMineEntry)({ ...form, title, description, linkUrl });
    if (!result.error) { opened.value = false; window.$message?.success('我的页面入口已保存'); await load(); }
  } finally { saving.value = false; }
}
async function toggle(row: Api.P3.MineEntry, enabled: boolean) {
  changing.value = true;
  try { await updateMineEntry({ ...row, enabled }); await load(); } finally { changing.value = false; }
}
async function remove(row: Api.P3.MineEntry) {
  changing.value = true;
  try { await deleteMineEntry(row.id); await load(); } finally { changing.value = false; }
}
async function reorder() {
  changing.value = true;
  try { await sortMineEntries(entries.value.map(row => row.id)); await load(); } finally { changing.value = false; }
}
onMounted(load);
</script>

<template>
  <CollapseCard title="我的页面入口管理">
    <template #extra><NSpace align="center"><span class="text-12px opacity-50">拖动手柄调整顺序</span><NButton type="primary" :disabled="loading || loadError || changing" @click="open()">新增我的入口</NButton></NSpace></template>
    <NAlert type="info" class="mb-16px">登录前后共用这些入口。没有启用入口时隐藏整组入口，直接展示下方帖子区域。</NAlert>
    <NAlert v-if="loadError" type="error">入口加载失败。<NButton size="small" @click="load">重试</NButton></NAlert>
    <NSpin v-else :show="loading || changing">
      <DragSortList v-model="entries" @reorder="reorder">
        <template #header="{ item }"><NSpace align="center" :size="12"><NImage :src="mediaUrl(item.iconUrl)" width="28" height="28" object-fit="contain" preview-disabled /><div><div class="font-medium">{{ item.title }}</div><div class="text-12px opacity-60">{{ item.description }}</div><div class="text-12px opacity-50 break-all">{{ item.linkUrl }}</div></div><NTag size="small">{{ item.targetType === 'app' ? 'APP 页面' : item.openMode === 'inapp' ? '网页 · 软件内' : '网页 · 浏览器' }}</NTag><NTag v-if="!item.enabled" size="small" type="warning">已停用</NTag></NSpace></template>
        <template #actions="{ item }"><NSwitch size="small" :value="item.enabled" :disabled="changing" @update:value="value => toggle(item, value)" /><NButton size="small" @click="open(item)">编辑</NButton><NPopconfirm @positive-click="remove(item)"><template #trigger><NButton size="small" type="error">删除</NButton></template>确定删除这个入口？</NPopconfirm></template>
      </DragSortList>
    </NSpin>
    <NModal v-model:show="opened" preset="card" :title="form.id ? '编辑我的入口' : '新增我的入口'" class="w-680px max-w-95vw" :mask-closable="!saving && !uploading" :close-on-esc="!saving && !uploading" :closable="!saving && !uploading">
      <NForm label-placement="left" label-width="100" :disabled="saving">
        <NFormItem label="标题" required><NInput v-model:value="form.title" maxlength="20" show-count placeholder="请输入入口标题" /></NFormItem>
        <NFormItem label="介绍" required><NInput v-model:value="form.description" maxlength="100" show-count placeholder="请输入入口介绍" /></NFormItem>
        <NFormItem label="SVG 图标" required>
          <NSpace vertical class="w-full">
            <NImage v-if="form.iconUrl" :src="mediaUrl(form.iconUrl)" width="48" height="48" object-fit="contain" preview-disabled />
            <NRadioGroup v-model:value="svgMode" :disabled="uploading"><NSpace><NRadio value="file">上传 SVG 文件</NRadio><NRadio value="code">粘贴 SVG 代码</NRadio></NSpace></NRadioGroup>
            <template v-if="svgMode === 'file'"><input ref="fileInput" type="file" accept=".svg,image/svg+xml" class="hidden" @change="selected"><NButton :loading="uploading" @click="fileInput?.click()">选择 SVG 文件</NButton></template>
            <template v-else><NInput v-model:value="svgCode" type="textarea" :disabled="uploading" :autosize="{ minRows: 4, maxRows: 8 }" placeholder="粘贴完整的 <svg xmlns=&quot;http://www.w3.org/2000/svg&quot; …>…</svg>" /><NButton :loading="uploading" @click="applyCode">应用 SVG 代码</NButton></template>
            <span class="text-12px opacity-50">最大256KB，支持纯矢量 SVG；应用成功后可在上方预览。</span>
          </NSpace>
        </NFormItem>
        <EntryTargetEditor v-model:target-type="form.targetType" v-model:link-url="form.linkUrl" v-model:open-mode="form.openMode" v-model:show-topbar="form.showTopbar" />
        <NFormItem label="排序"><NInputNumber v-model:value="form.sortOrder" :precision="0" :min="-2147483648" :max="2147483647" /></NFormItem>
        <NFormItem label="启用"><NSwitch v-model:value="form.enabled" /></NFormItem>
      </NForm>
      <template #footer><NSpace justify="end"><NButton :disabled="saving || uploading" @click="opened = false">取消</NButton><NButton type="primary" :loading="saving" :disabled="uploading" @click="save">保存</NButton></NSpace></template>
    </NModal>
  </CollapseCard>
</template>
