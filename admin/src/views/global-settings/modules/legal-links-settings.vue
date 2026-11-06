<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { fetchLegalLinks, saveLegalLinks, type LegalLinks } from '@/service/api/global-settings';

const auth = useAuthStore();
const loading = ref(true), saving = ref(false), failed = ref(false);
const rows: { key: keyof LegalLinks; title: string }[] = [
  { key: 'userAgreement', title: '用户协议' },
  { key: 'privacyPolicy', title: '隐私政策' },
  { key: 'personalInfoCollection', title: '个人信息收集清单' }
];
const form = ref<LegalLinks>({
  userAgreement: { linkUrl: '', openMode: 'browser' },
  privacyPolicy: { linkUrl: '', openMode: 'browser' },
  personalInfoCollection: { linkUrl: '', openMode: 'browser' }
});
async function load() {
  loading.value = true; failed.value = false;
  try {
    const result = await fetchLegalLinks();
    if (result.error || !result.data) { failed.value = true; return; }
    form.value = result.data;
  } catch { failed.value = true; }
  finally { loading.value = false; }
}
async function save() {
  if (loading.value || saving.value || failed.value || auth.isReadOnly) return;
  for (const row of rows) {
    const url = form.value[row.key].linkUrl.trim();
    if (url) {
      try {
        const parsed = new URL(url);
        if (url.length > 2048 || /\s/.test(url) || !['http:', 'https:'].includes(parsed.protocol) || !parsed.hostname || parsed.username || parsed.password) throw new Error();
      } catch {
        window.$message?.error(`${row.title}链接需为有效的 HTTP 或 HTTPS 完整地址（最多2048字符）`);
        return;
      }
    }
    form.value[row.key].linkUrl = url;
  }
  saving.value = true;
  try {
    const result = await saveLegalLinks(form.value);
    if (result.error || !result.data) { window.$message?.error('保存失败，请重试'); return; }
    form.value = result.data;
    window.$message?.success('协议链接已保存');
  } catch { window.$message?.error('保存失败，请重试'); }
  finally { saving.value = false; }
}
onMounted(load);
</script>

<template>
  <NCard title="设置页协议链接" :bordered="false" class="mt-16px">
    <NSpin :show="loading">
      <NAlert v-if="failed" type="error" class="mb-16px">
        协议链接加载失败，请重试。<NButton size="small" @click="load">重试</NButton>
      </NAlert>
      <NForm label-placement="top" :disabled="loading || saving || failed || auth.isReadOnly">
        <p class="mb-16px text-14px opacity-70">分别配置设置页三个入口的链接和打开方式。默认在外部浏览器打开；链接留空时，APP 提示暂未配置链接。</p>
        <div v-for="row in rows" :key="row.key" class="mb-20px">
          <NFormItem :label="row.title + '链接'">
            <NInput v-model:value="form[row.key].linkUrl" :input-props="{ 'aria-label': row.title + '链接' }" placeholder="https:// 开头的完整链接" :maxlength="2048" clearable />
          </NFormItem>
          <NFormItem :label="row.title + '打开方式'">
            <NRadioGroup v-model:value="form[row.key].openMode" role="radiogroup" :aria-label="row.title + '打开方式'">
              <NSpace><NRadio value="browser">外部浏览器打开</NRadio><NRadio value="inapp">APP 内打开</NRadio></NSpace>
            </NRadioGroup>
          </NFormItem>
        </div>
        <NButton type="primary" :loading="saving" :disabled="loading || saving || failed || auth.isReadOnly" @click="save">保存协议链接</NButton>
      </NForm>
    </NSpin>
  </NCard>
</template>
