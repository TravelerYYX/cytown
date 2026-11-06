<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { fetchEntryPages } from '@/service/api/home-config';

withDefaults(defineProps<{ appOnly?: boolean; maxLength?: number }>(), { appOnly: false, maxLength: 2048 });
const targetType = defineModel<'link' | 'app'>('targetType', { default: 'link' });
const linkUrl = defineModel<string>('linkUrl', { default: '' });
const openMode = defineModel<'inapp' | 'browser'>('openMode', { default: 'inapp' });
const showTopbar = defineModel<boolean>('showTopbar', { default: true });
const pages = ref<Api.P3.EntryPage[]>([]), loading = ref(false), failed = ref(false);
const selectedPage = computed(() => pages.value.find(page => page.value === linkUrl.value) ||
  pages.value.find(page => page.value.includes('=') && linkUrl.value.startsWith(page.value)));
async function loadPages() {
  loading.value = true; failed.value = false;
  try { const result = await fetchEntryPages(); if (result.error || !result.data) failed.value = true; else pages.value = result.data; }
  catch { failed.value = true; }
  finally { loading.value = false; }
}
function changeType(type: 'link' | 'app') {
  targetType.value = type;
  linkUrl.value = '';
}
onMounted(loadPages);
</script>

<template>
  <NFormItem v-if="!appOnly" label="跳转类型" required>
    <NRadioGroup :value="targetType" @update:value="changeType"><NSpace><NRadio value="link">跳转链接</NRadio><NRadio value="app">跳转 APP 页面</NRadio></NSpace></NRadioGroup>
  </NFormItem>
  <template v-if="appOnly || targetType === 'app'">
    <NFormItem label="APP 页面" required>
      <NSpace vertical class="w-full">
        <NSelect
          :value="selectedPage?.value"
          :options="pages"
          :loading="loading"
          :disabled="loading || failed"
          filterable
          :virtual-scroll="false"
          :show-checkmark="false"
          :menu-props="{ class: 'entry-page-select-menu' }"
          placeholder="请选择次元町内的页面"
          @update:value="value => linkUrl = value"
        />
        <NAlert v-if="failed" type="error">页面选项加载失败。<NButton size="small" @click="loadPages">重试</NButton></NAlert>
      </NSpace>
    </NFormItem>
    <NFormItem label="页面地址" required>
      <NSpace vertical class="w-full"><NInput v-model:value="linkUrl" :maxlength="maxLength" placeholder="选择页面后，可填写 ID 等页面参数" /><span class="text-12px opacity-60">{{ selectedPage?.hint || '直接打开次元町内的页面；页面自身处理登录和权限。' }}</span></NSpace>
    </NFormItem>
  </template>
  <template v-else>
    <NFormItem label="跳转链接" required><NInput v-model:value="linkUrl" maxlength="2048" placeholder="https:// 开头的完整链接" /></NFormItem>
    <NFormItem label="打开方式"><NRadioGroup v-model:value="openMode"><NSpace><NRadio value="inapp">软件内打开</NRadio><NRadio value="browser">外部浏览器打开</NRadio></NSpace></NRadioGroup></NFormItem>
    <NFormItem v-if="openMode === 'inapp'" label="显示顶部栏"><NSwitch v-model:value="showTopbar" /><span class="ml-8px">显示返回/标题/分享</span></NFormItem>
  </template>
</template>

<style scoped>
:global(.entry-page-select-menu .n-base-select-menu-option-wrapper) {
  display: grid !important;
  grid-template-columns: repeat(4, minmax(0, 1fr)) !important;
  gap: 6px !important;
  padding: 8px !important;
  box-sizing: border-box !important;
}

@media (max-width: 480px) {
  :global(.entry-page-select-menu .n-base-select-menu-option-wrapper) {
    grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
  }
}

:global(.entry-page-select-menu .n-base-select-option) {
  min-height: 34px !important;
  height: 34px !important;
  padding: 0 6px !important;
  border-radius: 6px !important;
  border: 1px solid var(--n-action-divider-color, rgba(128, 128, 128, 0.2)) !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  text-align: center !important;
  box-sizing: border-box !important;
  transition: all 0.2s ease !important;
}

:global(.entry-page-select-menu .n-base-select-option::before) {
  border-radius: 5px !important;
}

:global(.entry-page-select-menu .n-base-select-option .n-base-select-option__content) {
  text-align: center !important;
  width: 100% !important;
  font-size: 13px !important;
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
}

:global(.entry-page-select-menu .n-base-select-option.n-base-select-option--selected) {
  border-color: var(--n-option-text-color-active, #2080f0) !important;
  background-color: var(--n-option-color-pending, rgba(32, 128, 240, 0.12)) !important;
  color: var(--n-option-text-color-active, #2080f0) !important;
  font-weight: 500 !important;
}

:global(.entry-page-select-menu .n-base-select-option:hover) {
  border-color: var(--n-option-text-color-active, #2080f0) !important;
}

:global(.entry-page-select-menu .n-scrollbar) {
  max-height: 320px !important;
}
</style>
