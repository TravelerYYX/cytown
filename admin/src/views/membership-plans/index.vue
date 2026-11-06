<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import ImageUpload from '@/components/image-upload/index.vue';
import { loadMembership, saveMembership } from '@/service/api/membership';
import type { MembershipConfig, MembershipPlan } from '@/service/api/membership';
import { mediaUrl } from '@/utils/service';

const config = ref<MembershipConfig>();
const busy = ref(false);
const error = ref('');
const draft = ref<MembershipPlan>();
const isNew = ref(false);
const benefitsText = ref('');
const settings = ref({ selling: false, notice: '', contact: '', agreement: '' });
const plans = computed(() => {
  const rows = config.value?.plans.filter(plan => !plan.deleted) ?? [];
  return isNew.value && draft.value ? [...rows, draft.value] : rows;
});
function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}

async function load() {
  busy.value = true;
  error.value = '';
  try {
    const result = await loadMembership();
    if (result.data) {
      config.value = result.data;
      const { selling, notice, contact, agreement } = result.data;
      settings.value = { selling, notice, contact, agreement };
      draft.value = undefined;
      isNew.value = false;
    } else error.value = '加载失败，请重试';
  } finally {
    busy.value = false;
  }
}

async function persist(next: MembershipConfig, message: string) {
  if (busy.value) return false;
  busy.value = true;
  error.value = '';
  try {
    const result = await saveMembership(next);
    if (!result.data) {
      error.value = '保存失败，请检查配置；如果配置已被其他管理员更新，请重新加载';
      return false;
    }
    config.value = result.data;
    window.$message?.success(message);
    return true;
  } finally {
    busy.value = false;
  }
}

function changeEditor(action: () => void) {
  const original = config.value?.plans.find(plan => plan.id === draft.value?.id);
  if (
    draft.value &&
    (isNew.value ||
      JSON.stringify(draft.value) !== JSON.stringify(original) ||
      benefitsText.value !== original?.benefits.join('\n'))
  ) {
    window.$dialog?.warning({
      title: '放弃未保存的修改？',
      content: '当前套餐尚未保存，继续后将放弃这些修改。',
      positiveText: '放弃修改',
      negativeText: '继续编辑',
      onPositiveClick: action
    });
  } else action();
}

function edit(plan: MembershipPlan) {
  changeEditor(() => {
    draft.value = clone(plan);
    benefitsText.value = plan.benefits.join('\n');
    isNew.value = false;
  });
}
function add() {
  changeEditor(() => {
    draft.value = {
      id: 'plan-' + Date.now(),
      name: '新会员',
      description: '',
      priceFen: 900,
      experience: 0,
      coins: 0,
      badgeUrl: '',
      frameUrl: '',
      selling: false,
      archived: false,
      benefits: []
    };
    isNew.value = true;
    benefitsText.value = '';
  });
}
function cancel() {
  draft.value = undefined;
  isNew.value = false;
}
async function savePlan() {
  if (!config.value || !draft.value) return;
  const plan = clone(draft.value);
  plan.benefits = benefitsText.value
    .split('\n')
    .map(item => item.trim())
    .filter(Boolean);
  if (
    !plan.name.trim() ||
    plan.priceFen < 1 ||
    plan.benefits.some(benefit => benefit.length > 300) ||
    plan.benefits.length > 50
  ) {
    window.$message?.error('请填写会员名称和有效价格，权益最多 50 条，每条不超过 300 字');
    return;
  }
  plan.name = plan.name.trim();
  const next = clone(config.value);
  if (isNew.value) next.plans.push(plan);
  else next.plans = next.plans.map(item => (item.id === plan.id ? plan : item));
  if (await persist(next, isNew.value ? '套餐已添加' : '套餐已保存')) cancel();
}
async function toggle(plan: MembershipPlan, selling: boolean) {
  if (!config.value) return;
  const next = clone(config.value);
  next.plans = next.plans.map(item => (item.id === plan.id ? { ...item, selling } : item));
  if (await persist(next, selling ? '套餐已启用' : '套餐已停用')) {
    if (draft.value?.id === plan.id) draft.value.selling = selling;
  }
}
async function move(index: number, delta: number) {
  if (!config.value) return;
  const visible = config.value.plans.filter(plan => !plan.deleted);
  const target = index + delta;
  if (!visible[target]) return;
  [visible[index], visible[target]] = [visible[target], visible[index]];
  await persist(
    { ...clone(config.value), plans: [...visible, ...config.value.plans.filter(plan => plan.deleted)] },
    '套餐排序已保存'
  );
}
function remove(plan: MembershipPlan) {
  window.$dialog?.warning({
    title: '删除套餐',
    content: `确定删除「${plan.name}」？删除后将从套餐列表及购买页移除，已购会员权益和历史订单保留。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      if (!config.value) return false;
      const next = clone(config.value);
      next.plans = next.plans.filter(item => item.id !== plan.id);
      if (!(await persist(next, '套餐已删除'))) return false;
      if (draft.value?.id === plan.id) cancel();
      return true;
    }
  });
}
async function saveSettings() {
  if (config.value) await persist({ ...clone(config.value), ...clone(settings.value) }, '会员页面配置已保存');
}
onMounted(load);
</script>

<template>
  <NSpace vertical size="large">
    <NAlert v-if="error" type="error">{{ error }}</NAlert>
    <NCard title="会员套餐" :bordered="false">
      <template #header-extra>
        <NSpace>
          <NButton :disabled="busy" @click="changeEditor(load)">重新加载</NButton>
          <NButton type="primary" :disabled="!config || busy" @click="add">添加套餐</NButton>
        </NSpace>
      </template>
      <NText depth="3">一个月单次购买，无自动扣款。每笔成功订单赠送经验和次元币，会员到期不回收奖励。</NText>
      <NAlert v-if="config && !config.selling" type="info" class="mt-16px">
        全局购买开关已关闭，启用套餐后仍需在下方“会员页面与售卖设置”中开放购买。
      </NAlert>
      <NSpin :show="busy">
        <div class="plan-table mt-16px">
          <NTable :bordered="false" :single-line="true">
            <thead>
              <tr>
                <th>套餐</th>
                <th>月价</th>
                <th>每单赠送</th>
                <th>状态</th>
                <th>排序</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="(plan, index) in plans" :key="plan.id">
                <tr :data-plan-id="plan.id">
                  <td class="plan-summary">
                    <NSpace align="center" :wrap="false">
                      <NAvatar v-if="plan.badgeUrl" :src="mediaUrl(plan.badgeUrl)" :size="36" object-fit="contain" />
                      <div>
                        <NText strong>{{ plan.name }}</NText>
                        <div class="plan-description">
                          <NText depth="3">{{ plan.description || '暂无套餐说明' }}</NText>
                        </div>
                      </div>
                    </NSpace>
                  </td>
                  <td class="plan-price">
                    <NText type="primary" strong>¥{{ (plan.priceFen / 100).toFixed(2) }} / 月</NText>
                  </td>
                  <td>
                    <div>{{ plan.experience }} 经验</div>
                    <NText depth="3">{{ plan.coins }} 次元币</NText>
                  </td>
                  <td>
                    <NSpace align="center" :wrap="false">
                      <NSwitch
                        :value="plan.selling"
                        :disabled="busy || plan.archived || (isNew && draft?.id === plan.id)"
                        @update:value="value => toggle(plan, value)"
                      />
                      <NText :depth="plan.selling ? 1 : 3">
                        {{ plan.archived ? '已归档' : plan.selling ? '启用' : '停用' }}
                      </NText>
                    </NSpace>
                  </td>
                  <td>
                    <NSpace align="center" :wrap="false">
                      <NText>{{ index }}</NText>
                      <NButton size="tiny" :disabled="busy || index === 0 || isNew" @click="move(index, -1)">
                        上移
                      </NButton>
                      <NButton
                        size="tiny"
                        :disabled="busy || index === plans.length - 1 || isNew"
                        @click="move(index, 1)"
                      >
                        下移
                      </NButton>
                    </NSpace>
                  </td>
                  <td>
                    <NSpace :wrap="false">
                      <NButton
                        size="small"
                        :disabled="busy"
                        @click="draft?.id === plan.id ? changeEditor(cancel) : edit(plan)"
                      >
                        {{ draft?.id === plan.id ? '收起' : '编辑' }}
                      </NButton>
                      <NButton
                        size="small"
                        type="error"
                        secondary
                        :disabled="busy"
                        @click="isNew && draft?.id === plan.id ? changeEditor(cancel) : remove(plan)"
                      >
                        {{ isNew && draft?.id === plan.id ? '取消新增' : '删除' }}
                      </NButton>
                    </NSpace>
                  </td>
                </tr>
                <tr v-if="draft?.id === plan.id" class="editor-row">
                  <td colspan="6">
                    <NCard :title="isNew ? '添加会员套餐' : `编辑 · ${plan.name}`" size="small">
                      <NForm label-placement="top" :disabled="busy">
                        <NGrid cols="1 s:2" responsive="screen" :x-gap="24">
                          <NFormItemGi label="固定标识">
                            <NText depth="3">{{ draft.id }}</NText>
                          </NFormItemGi>
                          <NFormItemGi label="会员名称">
                            <NInput v-model:value="draft.name" maxlength="40" />
                          </NFormItemGi>
                          <NFormItemGi label="一个月价格（元）">
                            <NInputNumber
                              :value="draft.priceFen / 100"
                              :min="0.01"
                              :max="1000000"
                              :precision="2"
                              @update:value="
                                value => {
                                  if (draft) draft.priceFen = Math.round((value || 0) * 100);
                                }
                              "
                            />
                          </NFormItemGi>
                          <NFormItemGi label="每单赠送经验">
                            <NInputNumber v-model:value="draft.experience" :min="0" :max="1000000" :precision="0" />
                          </NFormItemGi>
                          <NFormItemGi label="每单赠送次元币">
                            <NInputNumber v-model:value="draft.coins" :min="0" :max="1000000" :precision="1" />
                          </NFormItemGi>
                          <NFormItemGi label="允许购买">
                            <NSwitch v-model:value="draft.selling" :disabled="draft.archived" />
                          </NFormItemGi>
                          <NFormItemGi label="归档（隐藏套餐并停止新购）">
                            <NSwitch
                              v-model:value="draft.archived"
                              @update:value="
                                value => {
                                  if (draft && value) draft.selling = false;
                                }
                              "
                            />
                          </NFormItemGi>
                        </NGrid>
                        <NFormItem label="会员权益（每行一条）">
                          <NInput
                            v-model:value="benefitsText"
                            type="textarea"
                            :autosize="{ minRows: 3, maxRows: 10 }"
                          />
                        </NFormItem>
                        <NFormItem label="权益下方文案（与会员标签一起展示）">
                          <NInput
                            v-model:value="draft.description"
                            type="textarea"
                            :autosize="{ minRows: 2, maxRows: 5 }"
                            maxlength="500"
                          />
                        </NFormItem>
                        <NGrid cols="1 s:2" responsive="screen" :x-gap="24">
                          <NFormItemGi label="会员标签图片（展示在权益下方）">
                            <NSpace vertical class="w-full">
                              <ImageUpload v-model="draft.badgeUrl" />
                              <NInput v-model:value="draft.badgeUrl" placeholder="标签图片地址" />
                            </NSpace>
                          </NFormItemGi>
                          <NFormItemGi label="会员头像框（透明中心）">
                            <NSpace vertical class="w-full">
                              <ImageUpload v-model="draft.frameUrl" />
                              <NInput v-model:value="draft.frameUrl" placeholder="头像框图片地址" />
                            </NSpace>
                          </NFormItemGi>
                        </NGrid>
                      </NForm>
                      <NSpace justify="end">
                        <NButton :disabled="busy" @click="changeEditor(cancel)">取消</NButton>
                        <NButton type="primary" :loading="busy" @click="savePlan">保存套餐</NButton>
                      </NSpace>
                    </NCard>
                  </td>
                </tr>
              </template>
              <tr v-if="config && !plans.length">
                <td colspan="6"><NEmpty description="暂无会员套餐，点击“添加套餐”创建" class="py-24px" /></td>
              </tr>
            </tbody>
          </NTable>
        </div>
      </NSpin>
    </NCard>
    <NCard v-if="config" :bordered="false">
      <NCollapse>
        <NCollapseItem title="会员页面与售卖设置" name="settings">
          <NForm label-placement="top" :disabled="busy">
            <NFormItem label="全局开放购买（开启前须配置支付宝渠道和服务协议）">
              <NSwitch v-model:value="settings.selling" />
            </NFormItem>
            <NFormItem label="公告"><NInput v-model:value="settings.notice" maxlength="500" /></NFormItem>
            <NFormItem label="反馈联系方式"><NInput v-model:value="settings.contact" maxlength="200" /></NFormItem>
            <NFormItem label="付费服务协议">
              <NInput
                v-model:value="settings.agreement"
                type="textarea"
                :autosize="{ minRows: 5, maxRows: 15 }"
                maxlength="50000"
              />
            </NFormItem>
          </NForm>
          <NSpace justify="end">
            <NButton type="primary" :loading="busy" @click="saveSettings">保存页面设置</NButton>
          </NSpace>
        </NCollapseItem>
      </NCollapse>
    </NCard>
  </NSpace>
</template>

<style scoped>
.plan-table {
  overflow-x: auto;
}
.plan-table :deep(table) {
  min-width: 1080px;
}
.plan-table :deep(th),
.plan-table :deep(td) {
  padding: 18px 16px;
}
.plan-summary {
  min-width: 240px;
  max-width: 320px;
}
.plan-description {
  max-width: 250px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  margin-top: 4px;
}
.plan-price {
  white-space: nowrap;
}
.editor-row > td {
  padding: 0 16px 16px;
}
</style>
