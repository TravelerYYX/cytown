<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue';
import { NAvatar, NButton } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { useAuthStore } from '@/store/modules/auth';
import { mediaUrl } from '@/utils/service';
import { chatAdminList, chatAdminDetail, chatAdminHistory, chatAdminDelete, chatAdminClear, chatAdminJobs, chatAdminRule, chatAdminAccountStatus } from '@/service/api/chat-admin';
import type { AdminConversation, AdminMessage, ChatPerson, ChatDeleteJob } from '@/service/api/chat-admin';
import ChatMedia from './modules/chat-media.vue';
defineOptions({ name: 'ChatManage' });
const auth = useAuthStore();
const allowed = computed(() => auth.userInfo.userType === 0 && auth.userInfo.roles.includes('R_SUPER'));
const keyword = ref(''), page = ref(1), total = ref(0), rows = ref<AdminConversation[]>([]), loading = ref(false), error = ref('');
const current = ref<AdminConversation | null>(null), visible = ref(false), messages = ref<AdminMessage[]>([]), older = ref(''), newer = ref(''), detailBusy = ref(false), detailError = ref(''), busy = ref(false), jobs = ref<ChatDeleteJob[]>([]), clock = ref(Date.now());
const ruleVisible = ref(false), target = ref<ChatPerson | null>(null), scope = ref('0'), minutes = ref<number | null>(60);
let listGeneration = 0, detailGeneration = 0, jobFlight = '';
const format = (value: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
function restriction(user: ChatPerson) {
  const text: string[] = []; if (user.deleted) text.push('已注销'); else if (user.status !== 0) text.push('账号已封禁');
  if (user.restriction.blocked) text.push('私聊已封禁');
  if (user.restriction.globalMutedUntil > clock.value) text.push(`全部私聊禁言至 ${format(user.restriction.globalMutedUntil)}`);
  if (user.restriction.conversationMutedUntil > clock.value) text.push(`当前会话禁言至 ${format(user.restriction.conversationMutedUntil)}`);
  return text.join('；') || '正常';
}
function personCell(user: ChatPerson) { return h('div', { class: 'flex items-center gap-10px' }, [h(NAvatar, { round: true, size: 38, src: mediaUrl(user.avatar), objectFit: 'cover' }, { default: () => user.avatar ? null : user.nickname.slice(0, 1), fallback: () => user.nickname.slice(0, 1) }), h('div', [h('div', user.nickname), h('div', { class: 'text-12px text-secondary' }, `账号：${user.account || '已注销'} / ${restriction(user)}`)])]); }
const columns: DataTableColumns<AdminConversation> = [
  { title: '用户 A', key: 'a', minWidth: 250, render: row => personCell(row.a) },
  { title: '与', key: 'pair', width: 45, render: () => '与' },
  { title: '用户 B', key: 'b', minWidth: 250, render: row => personCell(row.b) },
  { title: '最近消息', key: 'lastMessage', minWidth: 160, ellipsis: { tooltip: true } },
  { title: '最近消息时间', key: 'lastTime', width: 190, render: row => format(row.lastTime) },
  { title: '操作', key: 'action', width: 100, render: row => h(NButton, { size: 'small', onClick: () => open(row) }, { default: () => '查看对话' }) }
];
async function load() {
  if (!allowed.value) return; const generation = ++listGeneration; loading.value = true; error.value = '';
  try { const result = await chatAdminList(keyword.value.trim(), page.value); if (generation !== listGeneration) return; if (result.error || !result.data) throw new Error('会话加载失败，请重试'); rows.value = result.data.records; total.value = result.data.total; if (page.value > 1 && !rows.value.length) { page.value = Math.max(1, Math.ceil(total.value / 20)); await load(); } }
  catch (e) { if (generation === listGeneration) error.value = e instanceof Error ? e.message : '会话加载失败'; }
  finally { if (generation === listGeneration) loading.value = false; }
}
function search() { page.value = 1; void load(); }
async function history(before = '', after = '') {
  const id = current.value?.id; if (!id) return; const generation = ++detailGeneration; detailBusy.value = true; detailError.value = '';
  // Unmount media before replacing pages, aborting downloads and releasing object URLs.
  messages.value = [];
  try { const result = await chatAdminHistory(id, before, after); if (generation !== detailGeneration || current.value?.id !== id || !visible.value) return; if (result.error || !result.data) throw new Error('对话加载失败，请重试'); messages.value = result.data.records; older.value = result.data.older; newer.value = result.data.newer; }
  catch (e) { if (generation === detailGeneration) detailError.value = e instanceof Error ? e.message : '对话加载失败'; }
  finally { if (generation === detailGeneration) detailBusy.value = false; }
}
async function refreshDetail() { const id = current.value?.id; if (!id) return; const result = await chatAdminDetail(id); if (current.value?.id === id && result.data) current.value = result.data; }
async function pollJobs() { const id = current.value?.id; if (!id || !visible.value || jobFlight === id) return; jobFlight = id; try { const result = await chatAdminJobs(id); if (current.value?.id === id && result.data) jobs.value = result.data; } finally { if (jobFlight === id) jobFlight = ''; } }
function open(row: AdminConversation) { ++detailGeneration; current.value = row; visible.value = true; older.value = ''; newer.value = ''; jobs.value = []; void history(); void refreshDetail(); void pollJobs(); }
function close() { ++detailGeneration; messages.value = []; detailBusy.value = false; ruleVisible.value = false; }
function confirm(title: string, content: string, action: () => Promise<void>) {
  window.$dialog?.warning({ title, content, positiveText: '确认', negativeText: '取消', maskClosable: false, onPositiveClick: async () => { if (busy.value) return false; busy.value = true; try { await action(); } catch (e) { window.$message?.error(e instanceof Error ? e.message : '操作失败，请重试'); return false; } finally { busy.value = false; } return true; } });
}
function remove(message?: AdminMessage) {
  const conversation = current.value; if (!conversation) return;
  confirm(message ? '永久删除消息' : '清空云端会话', `${conversation.a.nickname}（${conversation.a.account}）与 ${conversation.b.nickname}（${conversation.b.account}）：${message ? '永久删除这条消息' : '永久删除当前已有的全部聊天记录'}及对应云端媒体，并在双方联网后移除应用内缓存。`, async () => {
    const result = message ? await chatAdminDelete(conversation.id, message.id) : await chatAdminClear(conversation.id);
    if (result.error || !result.data) throw new Error('删除失败，请重试');
    if (current.value?.id === conversation.id) { jobs.value = [result.data, ...jobs.value.filter(job => job.id !== result.data!.id)]; await history(); }
    window.$message?.success(result.data.state === 'completed' ? '已删除' : '已提交删除，正在清理云端记录和文件'); await load();
  });
}
function account(user: ChatPerson) { confirm(user.status === 0 ? '封禁账号' : '解封账号', `${user.nickname}（${user.account}）：${user.status === 0 ? '封禁后无法登录或继续使用账号' : '解除账号封禁'}。`, async () => { const result = await chatAdminAccountStatus(user.id, user.status === 0 ? 1 : 0); if (result.error) throw new Error('账号状态修改失败'); await refreshDetail(); await load(); }); }
function privateBlock(user: ChatPerson) { const action = user.restriction.blocked ? 'unblock' : 'block'; confirm(action === 'block' ? '封禁私聊' : '解除私聊封禁', `${user.nickname}（${user.account}）：${action === 'block' ? '永久禁止向任何人发送私聊，仍可接收和查看消息' : '解除永久私聊发送限制，其他禁言继续生效'}。`, async () => { const result = await chatAdminRule(user.id, '0', action); if (result.error) throw new Error('私聊限制修改失败'); await refreshDetail(); await load(); }); }
function openMute(user: ChatPerson) { target.value = user; scope.value = '0'; minutes.value = 60; ruleVisible.value = true; }
async function mute() { if (!target.value || !current.value || busy.value) return; if (!minutes.value || !Number.isSafeInteger(minutes.value) || minutes.value < 1) { window.$message?.warning('请输入正整数分钟数'); return; } busy.value = true; try { const result = await chatAdminRule(target.value.id, scope.value === '0' ? '0' : current.value.id, 'mute', minutes.value); if (result.error) return; ruleVisible.value = false; await refreshDetail(); await load(); } finally { busy.value = false; } }
function unmute(user: ChatPerson, pair: boolean) { const id = pair ? current.value?.id : '0'; if (!id) return; confirm('解除限时禁言', `解除 ${user.nickname}（${user.account}）的${pair ? '当前会话' : '全部私聊'}禁言，其他限制继续生效。`, async () => { const result = await chatAdminRule(user.id, id, 'unmute'); if (result.error) throw new Error('解除禁言失败'); await refreshDetail(); await load(); }); }
const timer = setInterval(() => { clock.value = Date.now(); if (jobs.value.some(job => job.state !== 'completed')) void pollJobs(); }, 2000);
onMounted(load);
onBeforeUnmount(() => { clearInterval(timer); ++listGeneration; ++detailGeneration; });
</script>
<template>
  <NCard v-if="allowed" title="用户私聊" :bordered="false" class="card-wrapper">
    <NSpace class="mb-16px"><NInput v-model:value="keyword" clearable maxlength="100" placeholder="任意一方昵称或登录账号" class="w-280px" @keyup.enter="search" /><NButton type="primary" :loading="loading" @click="search">查询</NButton><NButton @click="keyword = ''; search()">重置</NButton><NButton @click="load">刷新</NButton></NSpace>
    <NAlert v-if="error" type="error" class="mb-16px">{{ error }} <NButton text @click="load">重试</NButton></NAlert>
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(row: AdminConversation) => row.id" :scroll-x="1100" />
    <div class="mt-16px flex justify-end"><NPagination v-model:page="page" :page-size="20" :item-count="total" @update:page="load" /></div>
    <NModal v-model:show="visible" preset="card" :title="current ? `${current.a.nickname} 与 ${current.b.nickname}` : '对话详情'" class="chat-admin-dialog" :mask-closable="!busy" :close-on-esc="!busy" @after-leave="close">
      <template v-if="current">
        <div class="chat-admin-people">
          <div v-for="user in [current.a, current.b]" :key="user.id" class="chat-admin-person">
            <div class="flex items-center gap-12px"><NAvatar round :size="44" :src="mediaUrl(user.avatar)" object-fit="cover"><template v-if="!user.avatar" #default>{{ user.nickname.slice(0, 1) }}</template><template #fallback>{{ user.nickname.slice(0, 1) }}</template></NAvatar><div><strong>{{ user.nickname }}</strong><div>账号：{{ user.account || '已注销' }}</div></div></div>
            <div class="my-10px text-12px">{{ restriction(user) }}</div>
            <NSpace v-if="!user.deleted" :size="6">
              <NButton size="small" :disabled="busy" @click="account(user)">{{ user.status === 0 ? '封禁账号' : '解封账号' }}</NButton>
              <NButton size="small" :disabled="busy" @click="privateBlock(user)">{{ user.restriction.blocked ? '解除私聊封禁' : '封禁私聊' }}</NButton>
              <NButton size="small" :disabled="busy" @click="openMute(user)">限时禁言</NButton>
              <NButton v-if="user.restriction.globalMutedUntil > clock" size="small" :disabled="busy" @click="unmute(user, false)">解除全部私聊禁言</NButton>
              <NButton v-if="user.restriction.conversationMutedUntil > clock" size="small" :disabled="busy" @click="unmute(user, true)">解除当前会话禁言</NButton>
            </NSpace>
          </div>
        </div>
        <NSpace class="my-16px" justify="space-between"><NSpace><NButton :disabled="!older || detailBusy" @click="history(older)">更早一页</NButton><NButton :disabled="!newer || detailBusy" @click="history('', newer)">更新一页</NButton><NButton :disabled="detailBusy" @click="history(); refreshDetail(); pollJobs()">最新消息</NButton></NSpace><NButton type="error" :disabled="busy" @click="remove()">清空云端会话</NButton></NSpace>
        <NAlert v-if="detailError" type="error" class="mb-12px">{{ detailError }} <NButton text @click="history()">重试</NButton></NAlert>
        <div v-if="jobs.length" class="mb-12px"><div v-for="job in jobs.slice(0, 5)" :key="job.id" class="text-12px mb-4px">删除任务 {{ job.id.slice(0, 8) }}：{{ job.state === 'completed' ? '已完成' : job.state === 'retrying' ? '失败待重试（自动重试中）' : '清理中' }} {{ job.errorMessage }}<span v-if="job.remaining">，剩余 {{ job.remaining }} 个云端对象</span></div></div>
        <NSpin :show="detailBusy"><div class="chat-admin-messages">
          <NEmpty v-if="!detailBusy && !messages.length" description="暂无聊天记录" />
          <div v-for="message in messages" :key="message.id" class="chat-admin-message" :class="{ 'chat-admin-message-b': message.senderId === current.b.id }">
            <div class="chat-admin-message-meta">{{ message.senderId === current.a.id ? current.a.nickname : current.b.nickname }} · {{ format(message.sentAt) }}</div>
            <div class="chat-admin-bubble"><span v-if="message.revoked" class="text-secondary">消息已撤销</span><div v-else-if="message.kind === 'text'" class="chat-admin-text">{{ message.content }}</div><div v-else-if="message.kind === 'post' &amp;&amp; message.post" class="chat-admin-shared-post"><NImage v-if="message.post.coverUrl" :src="mediaUrl(message.post.coverUrl)" width="220" object-fit="cover" /><div>{{ message.post.video ? '[视频帖子] ' : '[帖子] ' }}{{ message.post.title }}</div><small>{{ message.post.authorName }}</small></div><ChatMedia v-else-if="message.asset" :asset="message.asset" :kind="message.kind" /><NButton text type="error" size="tiny" class="mt-8px" :disabled="busy" @click="remove(message)">删除消息</NButton></div>
          </div>
        </div></NSpin>
        <div class="mt-12px text-12px text-secondary">每页最多50条，翻页替换当前内容；后台查看不改变用户已读状态。</div>
      </template>
    </NModal>
    <NModal v-model:show="ruleVisible" preset="card" title="限时禁言" class="w-500px max-w-95vw" :mask-closable="!busy">
      <NForm label-placement="top"><NFormItem label="目标用户">{{ target?.nickname }}（{{ target?.account }}）</NFormItem><NFormItem label="生效范围"><NRadioGroup v-model:value="scope"><NSpace vertical><NRadio value="0">该用户全部私聊</NRadio><NRadio value="pair">仅禁止该用户向当前对方发消息</NRadio></NSpace></NRadioGroup></NFormItem><NFormItem label="快捷时长"><NSpace><NButton v-for="item in [{ text: '10分钟', value: 10 }, { text: '1小时', value: 60 }, { text: '1天', value: 1440 }, { text: '7天', value: 10080 }]" :key="item.value" size="small" @click="minutes = item.value">{{ item.text }}</NButton></NSpace></NFormItem><NFormItem label="自定义分钟数"><NInputNumber v-model:value="minutes" :min="1" :precision="0" /></NFormItem></NForm>
      <template #footer><NSpace justify="end"><NButton :disabled="busy" @click="ruleVisible = false">取消</NButton><NButton type="primary" :loading="busy" @click="mute">确认禁言</NButton></NSpace></template>
    </NModal>
  </NCard>
  <NResult v-else status="403" title="仅超级管理员可查看用户私聊" />
</template>
<style scoped>
.chat-admin-people{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.chat-admin-person{padding:14px;border:1px solid var(--n-border-color,#e6e8eb);border-radius:10px}.chat-admin-messages{height:52vh;min-height:250px;overflow:auto;background:var(--n-color-embedded,#f6f7f9);padding:18px;border-radius:10px}.chat-admin-message{display:flex;flex-direction:column;align-items:flex-start;margin-bottom:18px}.chat-admin-message-b{align-items:flex-end}.chat-admin-message-meta{font-size:12px;color:#89919b;margin-bottom:6px}.chat-admin-bubble{display:flex;flex-direction:column;align-items:flex-start;max-width:85%;padding:12px 16px;border-radius:10px;background:#fff;color:#26333e;border:1px solid #e6e8eb}.chat-admin-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7}.chat-admin-message-b .chat-admin-bubble{background:#e9f8f5;color:#253a36}@media(max-width:700px){.chat-admin-people{grid-template-columns:1fr}.chat-admin-messages{padding:10px}.chat-admin-bubble{max-width:95%}}
</style>
<style>
.chat-admin-dialog{width:1100px;max-width:96vw}
</style>
