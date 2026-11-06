<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { deleteRelease, fetchReleases, forceRelease, publishRelease, saveRelease, uploadRelease, withdrawRelease } from '@/service/api/app-releases';
import type { AppRelease, ReleaseDraft } from '@/service/api/app-releases';
const auth = useAuthStore();
const releases = ref<AppRelease[]>([]), selected = ref<AppRelease | null>(null), loading = ref(false), busy = ref(false);
const editor = ref(false), progress = ref<number | null>(null), baseline = ref(''), error = ref('');
const draft = ref<ReleaseDraft>({ versionName: '', versionCode: 101, notes: '', forceUpdate: false });
const dirty = computed(() => editor.value && JSON.stringify(draft.value) !== baseline.value);
const editable = computed(() => !auth.isReadOnly && (!selected.value || selected.value.status === 'draft'));
const current = computed(() => releases.value.find(r => r.current));
const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(2)} MB`;
function open(row?: AppRelease) {
  if (dirty.value && !window.confirm('有未保存的修改，确定放弃？')) return;
  selected.value = row || null;
  draft.value = row ? { versionName: row.versionName, versionCode: row.versionCode, notes: row.notes, forceUpdate: row.forceUpdate } : { versionName: '', versionCode: Math.max(100, ...releases.value.map(r => r.versionCode)) + 1, notes: '', forceUpdate: false };
  baseline.value = JSON.stringify(draft.value); editor.value = true; error.value = '';
}
async function load() { loading.value = true; try { const r = await fetchReleases(); if (!r.error && r.data) releases.value = r.data; } finally { loading.value = false; } }
async function save() {
  if (busy.value || !editable.value) return;
  busy.value = true; error.value = '';
  try {
    const r = await saveRelease(draft.value, selected.value?.id);
    if (!r.error && r.data) { selected.value = r.data; baseline.value = JSON.stringify(draft.value); await load(); window.$message?.success('草稿已保存，可上传 APK'); }
  } finally { busy.value = false; }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = '';
  if (!file || !selected.value || busy.value) return;
  if (!/\.apk$/i.test(file.name) || file.size <= 0 || file.size > 500 * 1024 * 1024) { error.value = '请选择不超过500MB的 APK 文件'; return; }
  if (dirty.value) { error.value = '请先保存草稿修改，再上传 APK'; return; }
  busy.value = true; progress.value = 0; error.value = '';
  try {
    const r = await uploadRelease(selected.value.id, file, n => { progress.value = n; });
    if (!r.error && r.data) {
      selected.value = r.data; draft.value.versionName = r.data.versionName; draft.value.versionCode = r.data.versionCode;
      baseline.value = JSON.stringify(draft.value); await load(); window.$message?.success('APK 已上传，版本信息已从安装包读取');
    } else error.value = '上传失败，原安装包已保留，请重试';
  } finally { busy.value = false; progress.value = null; }
}
function publish() {
  if (!selected.value || busy.value || dirty.value) return;
  const release = selected.value;
  const dialog = window.$dialog?.warning({
    title: '确认发布新版本',
    content: `确定发布 ${release.versionName}（${release.versionCode}）？${release.forceUpdate ? '此版本将强制更新，用户不能跳过。' : '用户可以稍后更新。'}`,
    positiveText: '确认发布',
    negativeText: '取消',
    maskClosable: false,
    closeOnEsc: false,
    closable: false,
    onNegativeClick: () => !busy.value,
    onPositiveClick: async () => {
      if (busy.value) return false;
      busy.value = true;
      if (dialog) dialog.loading = true;
      try {
        const r = await publishRelease(release.id);
        if (r.error || !r.data) return false;
        selected.value = r.data;
        await load();
        window.$message?.success('版本已发布');
      } finally {
        busy.value = false;
        if (dialog) dialog.loading = false;
      }
    }
  });
}
async function withdraw(row: AppRelease) {
  if (busy.value || !window.confirm('下架后客户端不再检测到此版本，也不会自动启用历史版本，确定下架？')) return;
  busy.value = true;
  try { const r = await withdrawRelease(row.id); if (!r.error) { await load(); if (selected.value?.id === row.id) selected.value = r.data || selected.value; } } finally { busy.value = false; }
}
function remove(row: AppRelease) {
  if (busy.value || auth.isReadOnly || row.current) return;
  const unsaved = dirty.value && selected.value?.id === row.id;
  const dialog = window.$dialog?.warning({
    title: '确认删除版本',
    content: `确定删除 ${row.versionName}（${row.versionCode}）？版本信息和关联的 APK 文件将一并删除，删除后无法恢复。${unsaved ? '此版本尚未保存的修改也将丢弃。' : ''}`,
    positiveText: '确认删除',
    negativeText: '取消',
    maskClosable: false,
    closeOnEsc: false,
    closable: false,
    onNegativeClick: () => !busy.value,
    onPositiveClick: async () => {
      if (busy.value || auth.isReadOnly) return false;
      busy.value = true;
      if (dialog) dialog.loading = true;
      try {
        const r = await deleteRelease(row.id);
        if (r.error) return false;
        releases.value = releases.value.filter(release => release.id !== row.id);
        if (selected.value?.id === row.id) {
          selected.value = null;
          editor.value = false;
          baseline.value = '';
          error.value = '';
        }
        await load();
        window.$message?.success('版本信息和 APK 已删除');
      } finally {
        busy.value = false;
        if (dialog) dialog.loading = false;
      }
    }
  });
}
async function toggleForce(row: AppRelease, value: boolean) {
  if (busy.value) return;
  if (dirty.value && selected.value?.id === row.id) { error.value = '请先保存草稿修改，再切换强制更新'; return; }
  if (value && !window.confirm('开启后，检测到此版本的旧客户端将不能跳过更新，确定开启？')) return;
  busy.value = true;
  try { const r = await forceRelease(row.id, value); if (!r.error && r.data) { await load(); if (selected.value?.id === row.id) { selected.value = r.data; draft.value.forceUpdate = value; baseline.value = JSON.stringify(draft.value); } } } finally { busy.value = false; }
}
onBeforeRouteLeave(() => !busy.value && (!dirty.value || window.confirm('修改尚未保存，确定离开？')));
onMounted(load);
</script>
<template>
  <div class="flex flex-col gap-16px">
    <NCard title="应用更新"><template #header-extra><NSpace><NButton :loading="loading" :disabled="busy" @click="load">刷新</NButton><NButton v-if="!auth.isReadOnly" type="primary" :disabled="busy" @click="open()">新建版本</NButton></NSpace></template>
      <NAlert type="info">仅支持 Android APK，单包最大500MB。上传后保存为草稿，发布后客户端才会检测到。强制更新默认关闭。</NAlert>
      <div class="mt-16px">当前生效版本：<NTag :type="current ? 'success' : 'default'">{{ current ? `${current.versionName}（${current.versionCode}）` : '暂无' }}</NTag></div>
    </NCard>
    <NCard v-if="editor" :title="selected ? `${selected.status === 'draft' ? '编辑草稿' : '版本详情'} #${selected.id}` : '新建版本草稿'">
      <NAlert v-if="error" type="error" class="mb-16px">{{ error }}</NAlert>
      <NForm label-placement="top" :disabled="busy || !editable">
        <div class="release-fields"><NFormItem label="版本名称"><NInput v-model:value="draft.versionName" placeholder="例如 1.0.1" :maxlength="64" /></NFormItem><NFormItem label="版本号 versionCode"><NInputNumber v-model:value="draft.versionCode" :min="1" :max="2100000000" :precision="0" /></NFormItem></div>
        <NFormItem label="更新说明"><NInput v-model:value="draft.notes" type="textarea" :autosize="{ minRows: 4, maxRows: 12 }" :maxlength="10000" show-count placeholder="向用户说明这次更新内容" /></NFormItem>
        <NFormItem label="强制更新"><NSwitch v-model:value="draft.forceUpdate" /><span class="ml-12px">开启后用户不能跳过更新</span></NFormItem>
      </NForm>
      <NSpace v-if="editable" class="mb-16px"><NButton type="primary" :loading="busy && progress === null" :disabled="busy" @click="save">保存草稿</NButton><label v-if="selected" class="upload-button" :class="{ disabled: busy || dirty }">{{ selected.packageUrl ? '替换 APK' : '上传 APK' }}<input type="file" accept=".apk,application/vnd.android.package-archive" :disabled="busy || dirty" @change="upload" /></label><NButton v-if="selected?.packageUrl" type="success" :disabled="busy || dirty" @click="publish">发布版本</NButton></NSpace>
      <NProgress v-if="progress !== null" type="line" :percentage="Math.floor(progress * 10) / 10" :processing="progress < 100" />
      <p v-if="progress === 100">文件已传输，正在校验并写入存储，请稍候。</p>
      <NDescriptions v-if="selected?.packageUrl" bordered :column="1" class="mt-16px"><NDescriptionsItem label="包名">{{ selected.packageName }}</NDescriptionsItem><NDescriptionsItem label="安装包大小">{{ size(selected.sizeBytes) }}</NDescriptionsItem><NDescriptionsItem label="SHA-256"><span class="digest">{{ selected.sha256 }}</span></NDescriptionsItem></NDescriptions>
      <NText v-if="editable" depth="3">上传会从 APK 读取并填写真实版本信息。已发布版本的安装包与版本号不能修改。</NText>
    </NCard>
    <NCard title="历史版本"><NSpin :show="loading"><NEmpty v-if="!releases.length" description="暂无版本，点击新建版本开始" /><div v-else class="history-scroll"><NTable :single-line="false"><thead><tr><th>版本</th><th>状态</th><th>大小</th><th>强制更新</th><th>发布时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in releases" :key="row.id"><td>{{ row.versionName }}<div class="text-12px">{{ row.versionCode }}</div></td><td><NTag :type="row.current ? 'success' : row.status === 'draft' ? 'warning' : 'default'">{{ row.current ? '当前生效' : row.status === 'draft' ? '草稿' : row.status === 'withdrawn' ? '已下架' : '历史版本' }}</NTag></td><td>{{ row.sizeBytes ? size(row.sizeBytes) : '未上传' }}</td><td><NSwitch :value="row.forceUpdate" :disabled="busy || auth.isReadOnly" @update:value="value => toggleForce(row, value)" /></td><td>{{ row.publishedAt || '尚未发布' }}</td><td><NSpace><NButton size="small" :disabled="busy" @click="open(row)">{{ row.status === 'draft' && !auth.isReadOnly ? '编辑' : '详情' }}</NButton><NButton v-if="row.current && !auth.isReadOnly" size="small" type="warning" :disabled="busy" @click="withdraw(row)">下架</NButton><NButton v-if="!row.current && !auth.isReadOnly" size="small" type="error" :disabled="busy" @click="remove(row)">删除</NButton></NSpace></td></tr></tbody></NTable></div></NSpin></NCard>
  </div>
</template>
<style scoped>
.release-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.history-scroll{overflow-x:auto}.digest{overflow-wrap:anywhere}.upload-button{display:inline-flex;align-items:center;justify-content:center;height:34px;padding:0 14px;border:1px solid #d9d9d9;border-radius:3px;cursor:pointer}.upload-button input{display:none}.upload-button.disabled{opacity:.5;cursor:not-allowed}@media(max-width:600px){.release-fields{grid-template-columns:1fr;gap:0}}
</style>
