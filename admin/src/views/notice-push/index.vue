<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { request } from '@/service/request';
import { useAuthStore } from '@/store/modules/auth';
interface PushConfig { enabled: boolean; appId: string; endpoint: string; secret: string; secretConfigured: boolean }
interface OfflineReminder { enabled: boolean }
const auth = useAuthStore();
const offlineReminder = reactive<OfflineReminder>({ enabled: false });
const reminderLoading = ref(true), reminderSaving = ref(false), reminderError = ref('');
const form = reactive<PushConfig>({ enabled: false, appId: '__UNI__B1A811F', endpoint: '', secret: '', secretConfigured: false });
const saving = ref(false), testing = ref(false), cid = ref('');
onMounted(async () => { const r = await request<PushConfig>({ url: '/notice/push/config' }); if (r.data) Object.assign(form, r.data); });
onMounted(loadOfflineReminder);
async function loadOfflineReminder() {
  reminderLoading.value = true;
  try {
    const r = await request<OfflineReminder>({ url: '/chat-settings/offline-reminder' });
    if (r.data) { Object.assign(offlineReminder, r.data); reminderError.value = ''; }
    else reminderError.value = '私聊离线提醒配置读取失败，请重试';
  } finally { reminderLoading.value = false; }
}
async function saveOfflineReminder() {
  reminderSaving.value = true;
  try {
    const r = await request<OfflineReminder>({ url: '/chat-settings/offline-reminder', method: 'post', data: offlineReminder });
    if (!r.error && r.data) { Object.assign(offlineReminder, r.data); window.$message?.success('私聊离线消息提醒配置已保存'); }
  } finally { reminderSaving.value = false; }
}
async function save() {
  saving.value = true;
  try { const r = await request<PushConfig>({ url: '/notice/push/config', method: 'post', data: form }); if (!r.error && r.data) { Object.assign(form, r.data); window.$message?.success('系统推送配置已保存'); } }
  finally { saving.value = false; }
}
async function test() {
  if (!cid.value.trim()) { window.$message?.warning('请输入测试设备的推送标识'); return; }
  testing.value = true;
  try { const r = await request({ url: '/notice/push/test', method: 'post', data: { cid: cid.value.trim() } }); if (!r.error) window.$message?.success('推送服务已受理，请在测试手机确认接收'); }
  finally { testing.value = false; }
}
</script>
<template>
  <NCard title="系统推送配置" :bordered="false">
    <NAlert type="info" class="mb-20px">站内通知始终独立可用。启用 Android 系统通知前，请开通 UniPush 2、部署推送桥接云函数、配置厂商通道并重新打包 APP。</NAlert>
    <NForm label-placement="top" class="max-w-720px">
      <NFormItem label="启用系统推送"><NSwitch v-model:value="form.enabled" /></NFormItem>
      <NFormItem label="DCloud 应用标识"><NInput v-model:value="form.appId" placeholder="__UNI__..." /></NFormItem>
      <NFormItem label="推送桥接接口（HTTPS）"><NInput v-model:value="form.endpoint" placeholder="部署云函数后取得的 HTTPS 地址" /></NFormItem>
      <NFormItem :label="form.secretConfigured ? '签名密钥（已配置，留空保留）' : '签名密钥（至少32位）'"><NInput v-model:value="form.secret" type="password" show-password-on="click" autocomplete="new-password" /></NFormItem>
      <NButton type="primary" :loading="saving" @click="save">保存配置</NButton>
      <NDivider />
      <NFormItem label="测试设备推送标识 CID"><NInput v-model:value="cid" placeholder="在已安装的 Android APP 设置页查看" /></NFormItem>
      <NButton :disabled="!form.enabled || !form.secretConfigured" :loading="testing" @click="test">使用已保存配置发送测试通知</NButton>
    </NForm>
    <NDivider title-placement="left">私聊离线通知</NDivider>
    <NSpin :show="reminderLoading">
      <NAlert v-if="reminderError" type="error" class="mb-16px">
        {{ reminderError }}
        <NButton text type="primary" class="ml-12px" @click="loadOfflineReminder">重新读取</NButton>
      </NAlert>
      <NForm label-placement="top" class="max-w-720px" :disabled="auth.isReadOnly || reminderLoading || reminderSaving || !!reminderError">
        <NFormItem label="私聊离线消息提醒"><NSwitch v-model:value="offlineReminder.enabled" aria-label="私聊离线消息提醒" /></NFormItem>
        <NAlert type="info" class="mb-20px">开启后通过系统推送提醒未读私聊消息，需同时开启并配置上方系统推送。关闭后取消尚未发送的私聊提醒；聊天收发、未读消息和站内通知照常保留。重新开启仅提醒之后新发送的消息。</NAlert>
        <NButton type="primary" :loading="reminderSaving" :disabled="auth.isReadOnly || reminderLoading || !!reminderError" @click="saveOfflineReminder">保存私聊离线提醒配置</NButton>
      </NForm>
    </NSpin>
  </NCard>
</template>
