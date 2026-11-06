<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { fetchGlobalSettings, saveGlobalSettings } from '@/service/api/global-settings';
import LegalLinksSettings from './modules/legal-links-settings.vue';

defineOptions({ name: 'GlobalSettings' });
const auth = useAuthStore();
const loading = ref(true), saving = ref(false), failed = ref(false);
const deleteResourceFilesEnabled = ref(true);
async function load() {
  loading.value = true; failed.value = false;
  try {
    const result = await fetchGlobalSettings();
    if (result.error || !result.data) { failed.value = true; return; }
    deleteResourceFilesEnabled.value = result.data.deleteResourceFilesEnabled;
  } catch { failed.value = true; }
  finally { loading.value = false; }
}
async function save() {
  if (loading.value || saving.value || failed.value || auth.isReadOnly) return;
  saving.value = true;
  try {
    const result = await saveGlobalSettings({ deleteResourceFilesEnabled: deleteResourceFilesEnabled.value });
    if (result.error || !result.data) { window.$message?.error('保存失败，请重试'); return; }
    deleteResourceFilesEnabled.value = result.data.deleteResourceFilesEnabled;
    window.$message?.success('全局设置已保存');
  } catch { window.$message?.error('保存失败，请重试'); }
  finally { saving.value = false; }
}
onMounted(load);
</script>

<template>
  <div>
  <NCard title="全局设置" :bordered="false">
    <NSpin :show="loading">
      <NAlert v-if="failed" type="error" class="mb-16px">
        设置加载失败，请重试。<NButton size="small" @click="load">重试</NButton>
      </NAlert>
      <NForm label-placement="left" label-width="230" :disabled="loading || saving || failed || auth.isReadOnly">
        <NFormItem label="删除时同时删除资源文件">
          <NSwitch v-model:value="deleteResourceFilesEnabled" aria-label="删除时同时删除资源文件" />
        </NFormItem>
        <p class="mb-16px text-14px opacity-70">关闭后，删除内容、替换资源及自动清理过期上传时保留资源文件；开启后仅清理无其他业务引用的文件。</p>
        <p class="mb-16px text-14px opacity-70">关闭期间暂停待重试的清理任务，重新开启后继续；不会追溯清理关闭期间保留的文件。</p>
        <NButton type="primary" :loading="saving" :disabled="loading || failed || auth.isReadOnly" @click="save">保存全局设置</NButton>
      </NForm>
    </NSpin>
  </NCard>
  <LegalLinksSettings />
  </div>
</template>
