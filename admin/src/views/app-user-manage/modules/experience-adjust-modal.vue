<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { fetchAppUserDetail, fetchOfficialAccountDetail } from '@/service/api';
import { adjustExperience, fetchExperienceHistory } from '@/service/api/experience-adjustment';
import type { ExperienceAdjustmentRecord, ExperienceAdjustmentResult } from '@/service/api/experience-adjustment';

const props = defineProps<{ show: boolean; kind: 'app' | 'official'; user: { id: string; account: string } | null }>();
const emit = defineEmits<{ 'update:show': [value: boolean]; saved: [] }>();
const MAX_EXP = 1_000_000_000_000;
const loading = ref(false), saving = ref(false), failed = ref(false), unavailable = ref(false);
const profile = ref<Api.AppUser.Detail | null>(null);
const form = reactive({ direction: 'add', amount: null as number | null, reason: '' });
const delta = computed(() => (form.amount || 0) * (form.direction === 'add' ? 1 : -1));
const preview = computed(() => profile.value ? profile.value.experience + delta.value : null);
const valid = computed(() => profile.value && Number.isSafeInteger(form.amount) && (form.amount || 0) > 0
  && (form.amount || 0) <= MAX_EXP && preview.value !== null && preview.value >= 0 && preview.value <= MAX_EXP
  && form.reason.trim().length > 0 && [...form.reason.trim()].length <= 200);
const lastResult = ref<ExperienceAdjustmentResult | null>(null);
const records = ref<ExperienceAdjustmentRecord[]>([]), historyLoading = ref(false), historyFailed = ref(false);
const page = ref(1), total = ref(0);
let generation = 0, historyRevision = 0, attemptSignature = '', attemptId = '';
function newRequestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
  const value = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}
function close(value: boolean) { if (!saving.value) emit('update:show', value); }
async function loadProfile(current = generation) {
  if (!props.user) return;
  loading.value = true; failed.value = false; unavailable.value = false;
  try {
    const response = props.kind === 'app' ? await fetchAppUserDetail(props.user.id) : await fetchOfficialAccountDetail(props.user.id);
    if (current !== generation) return;
    if (response.error || !response.data) { failed.value = true; profile.value = null; return; }
    const data = props.kind === 'app' ? response.data as Api.AppUser.Detail : (response.data as Api.AdminUser.Detail).community;
    profile.value = data;
    unavailable.value = !data;
  } catch { if (current === generation) { failed.value = true; profile.value = null; } }
  finally { if (current === generation) loading.value = false; }
}
async function loadHistory() {
  if (!props.user || !profile.value) return;
  const current = generation, revision = ++historyRevision;
  historyLoading.value = true; historyFailed.value = false;
  try {
    const { data, error } = await fetchExperienceHistory(props.kind, props.user.id, page.value);
    if (current !== generation || revision !== historyRevision) return;
    if (error || !data) { historyFailed.value = true; return; }
    records.value = data.records; total.value = data.total;
  } catch { if (current === generation && revision === historyRevision) historyFailed.value = true; }
  finally { if (current === generation && revision === historyRevision) historyLoading.value = false; }
}
async function reload() { const current = generation; await loadProfile(current); if (current === generation) await loadHistory(); }
watch(() => [props.show, props.kind, props.user?.id], () => {
  generation++; historyRevision++;
  profile.value = null; records.value = []; page.value = 1; total.value = 0;
  loading.value = false; historyLoading.value = false; failed.value = false; historyFailed.value = false;
  unavailable.value = false; lastResult.value = null;
  form.direction = 'add'; form.amount = null; form.reason = ''; attemptSignature = ''; attemptId = '';
  if (props.show && props.user) void reload();
}, { immediate: true });
// Editing a failed request creates a new operation, even when the value is later changed back.
watch(() => [form.direction, form.amount, form.reason], () => { attemptSignature = ''; attemptId = ''; }, { flush: 'sync' });
async function submit() {
  if (saving.value || loading.value || !valid.value || !props.user) return;
  const current = generation, id = props.user.id, kind = props.kind;
  const reason = form.reason.trim(), amount = delta.value;
  const signature = JSON.stringify([kind, id, amount, reason]);
  if (signature !== attemptSignature) { attemptSignature = signature; attemptId = newRequestId(); }
  saving.value = true;
  try {
    const { data, error } = await adjustExperience(kind, { id, delta: amount, reason, requestId: attemptId });
    if (current !== generation || error || !data) return;
    lastResult.value = data;
    if (profile.value) { profile.value.experience = data.afterExperience; profile.value.level = data.afterLevel; }
    form.amount = null; form.reason = ''; page.value = 1;
    window.$message?.success(`积分已调整：${data.delta > 0 ? '+' : ''}${data.delta}，当前积分 ${data.afterExperience}，等级 Lv.${data.afterLevel}`);
    emit('saved');
    await reload();
  } catch { if (current === generation) window.$message?.error('提交失败，请重试；相同请求不会重复调整'); }
  finally { saving.value = false; }
}
const columns: DataTableColumns<ExperienceAdjustmentRecord> = [
  { title: '时间', key: 'createTime', width: 170, render: row => row.createTime?.replace('T', ' ') },
  { title: '操作人', key: 'operatorId', width: 160, render: row => `${row.operatorName || '管理员'}（${row.operatorId}）` },
  { title: '增减积分', key: 'delta', width: 110, render: row => `${row.delta > 0 ? '+' : ''}${row.delta}` },
  { title: '积分变化', key: 'afterExperience', width: 200, render: row => `${row.beforeExperience} → ${row.afterExperience}` },
  { title: '等级变化', key: 'afterLevel', width: 120, render: row => `Lv.${row.beforeLevel} → Lv.${row.afterLevel}` },
  { title: '原因', key: 'reason', minWidth: 200, ellipsis: { tooltip: true } }
];
</script>

<template>
  <NModal :show="show" preset="card" :title="`调整积分：${user?.account || ''}`" class="w-960px max-w-95vw"
    :mask-closable="!saving" :close-on-esc="!saving" :closable="!saving" @update:show="close">
    <div class="max-h-75vh overflow-y-auto">
      <NSpin :show="loading">
        <NAlert v-if="failed" type="error" class="mb-16px">用户积分加载失败 <NButton text @click="reload">重试</NButton></NAlert>
        <NAlert v-else-if="unavailable" type="warning">该官方账号尚未建立社区身份，无法调整积分。</NAlert>
        <template v-else-if="profile">
          <NAlert type="info" class="mb-16px">当前积分：{{ profile.experience }}，等级：Lv.{{ profile.level }}。调整会自动重算等级，不额外发放跨段奖励。</NAlert>
          <NAlert v-if="lastResult" type="success" class="mb-16px">本次已调整 {{ lastResult.delta > 0 ? '+' : '' }}{{ lastResult.delta }} 积分；{{ lastResult.beforeExperience }} → {{ lastResult.afterExperience }}，Lv.{{ lastResult.beforeLevel }} → Lv.{{ lastResult.afterLevel }}。</NAlert>
          <NForm :disabled="saving || loading" label-placement="left" label-width="90">
            <NFormItem label="调整方式"><NRadioGroup v-model:value="form.direction"><NRadioButton value="add">增加积分</NRadioButton><NRadioButton value="deduct">扣减积分</NRadioButton></NRadioGroup></NFormItem>
            <NFormItem label="积分数量"><NInputNumber v-model:value="form.amount" :min="1" :max="MAX_EXP" :precision="0" placeholder="请输入正整数" class="w-full" /></NFormItem>
            <NFormItem label="调整原因" required><NInput v-model:value="form.reason" type="textarea" maxlength="200" show-count placeholder="填写本次积分调整的原因，仅管理员可见" /></NFormItem>
            <NText v-if="form.amount !== null" :type="preview !== null && preview >= 0 && preview <= MAX_EXP ? 'default' : 'error'">预计调整后积分：{{ preview }}（允许范围：0～1,000,000,000,000）</NText>
          </NForm>
          <NDivider>人工调整记录</NDivider>
          <NAlert v-if="historyFailed" type="error" class="mb-12px">记录加载失败 <NButton text @click="loadHistory">重试</NButton></NAlert>
          <NDataTable :columns="columns" :data="records" :loading="historyLoading" :row-key="(row: ExperienceAdjustmentRecord) => row.id" :scroll-x="960" />
          <div class="mt-12px flex justify-end"><NPagination v-model:page="page" :page-size="20" :item-count="total" :disabled="saving" @update:page="loadHistory" /></div>
        </template>
        <div v-else-if="loading" class="h-100px" />
      </NSpin>
    </div>
    <template #footer><NSpace justify="end"><NButton :disabled="saving" @click="close(false)">关闭</NButton><NButton type="primary" :disabled="!valid || loading" :loading="saving" @click="submit">确认调整</NButton></NSpace></template>
  </NModal>
</template>
