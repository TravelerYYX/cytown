<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import { NButton, NPopconfirm, NTag } from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  fetchOfficialAccountCreate,
  fetchOfficialAccountDelete,
  fetchOfficialAccountList,
  fetchOfficialAccountUpdateStatus
} from '@/service/api';

import UserEditModal from './user-edit-modal.vue';
import UserDetailModal from './user-detail-modal.vue';
import ExperienceAdjustModal from './experience-adjust-modal.vue';
import { validateAccount, validatePassword } from './user-validation';
import { useAuthStore } from '@/store/modules/auth';

defineOptions({ name: 'AppUserOfficialAccount' });
const authStore = useAuthStore();
const canDelete = computed(() => authStore.userInfo.userType === 0 && authStore.userInfo.roles.includes('R_SUPER'));
const editVisible = ref(false);
const detailVisible = ref(false);
const experienceVisible = ref(false);
const selected = ref<{ id: string; account: string } | null>(null);
function openEdit(row: Api.AdminUser.ListItem) { selected.value = { id: row.id, account: row.username }; editVisible.value = true; }
function openDetail(row: Api.AdminUser.ListItem) { selected.value = { id: row.id, account: row.username }; detailVisible.value = true; }
function openExperience(row: Api.AdminUser.ListItem) { selected.value = { id: row.id, account: row.username }; experienceVisible.value = true; }

const keyword = ref('');
/** 状态筛选：-1 表示全部 */
const statusFilter = ref<-1 | 0 | 1>(-1);
const query = reactive<Api.AdminUser.QueryParam>({ pageNum: 1, pageSize: 10 });
const rows = ref<Api.AdminUser.ListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const errorMessage = ref('');
const busyId = ref('');
const busyAction = ref<'status' | 'delete' | ''>('');
let requestId = 0;

async function load() {
  const current = ++requestId;
  loading.value = true;
  errorMessage.value = '';
  try {
    const { data, error } = await fetchOfficialAccountList({
      ...query,
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value === -1 ? undefined : statusFilter.value
    });
    if (current !== requestId) return;
    if (error || !data) {
      errorMessage.value = '官方账号加载失败，请重试';
      return;
    }
    rows.value = data.records;
    total.value = data.total;
    if (query.pageNum > 1 && !data.records.length) {
      query.pageNum = Math.max(1, Math.ceil(data.total / query.pageSize));
      await load();
    }
  } catch {
    if (current === requestId) errorMessage.value = '官方账号加载失败，请重试';
  } finally {
    if (current === requestId) loading.value = false;
  }
}

function search() {
  query.pageNum = 1;
  void load();
}
function reset() {
  keyword.value = '';
  statusFilter.value = -1;
  search();
}

const statusOptions: SelectOption[] = [
  { label: '全部状态', value: -1 },
  { label: '正常', value: 0 },
  { label: '禁用', value: 1 }
];

// ---------- 创建 ----------
const createVisible = ref(false);
const creating = ref(false);
const createForm = reactive({ account: '', nickname: '', password: '', confirm: '' });

watch(createVisible, visible => { if (!visible) { createForm.password = ''; createForm.confirm = ''; } });

function openCreate() {
  createForm.account = '';
  createForm.nickname = '';
  createForm.password = '';
  createForm.confirm = '';
  createVisible.value = true;
}

async function submitCreate() {
  if (creating.value) return;
  const account = createForm.account.trim();
  const accountError = validateAccount(account);
  if (accountError) { window.$message?.warning(accountError); return; }
  const invalid = validatePassword(createForm.password);
  if (invalid) {
    window.$message?.warning(invalid);
    return;
  }
  if (createForm.password !== createForm.confirm) {
    window.$message?.warning('两次输入的密码不一致');
    return;
  }
  creating.value = true;
  try {
    const { data, error } = await fetchOfficialAccountCreate(account, createForm.password, createForm.nickname.trim());
    if (!error && data) {
      createVisible.value = false;
      window.$message?.success(`官方账号已创建：${data.username}，请将账号与初始密码告知使用者`);
      await load();
    }
  } finally {
    creating.value = false;
  }
}

// ---------- 启停 ----------
async function changeStatus(row: Api.AdminUser.ListItem) {
  if (busyId.value) return;
  busyId.value = row.id;
  busyAction.value = 'status';
  try {
    const { error } = await fetchOfficialAccountUpdateStatus(row.id, row.status === 0 ? 1 : 0);
    if (!error) {
      window.$message?.success(row.status === 0 ? '账号已禁用' : '账号已启用');
      await load();
    }
  } finally {
    busyId.value = '';
    busyAction.value = '';
  }
}

async function deleteUser(row: Api.AdminUser.ListItem) {
  if (busyId.value || !canDelete.value) return;
  busyId.value = row.id;
  busyAction.value = 'delete';
  try {
    const { error } = await fetchOfficialAccountDelete(row.id);
    if (error) { window.$message?.error('删除失败，请重试'); return; }
    window.$message?.success('官方账号已删除');
    await load();
  } catch {
    window.$message?.error('删除失败，请重试');
  } finally { busyId.value = ''; busyAction.value = ''; }
}

const columns: DataTableColumns<Api.AdminUser.ListItem> = [
  { title: 'ID', key: 'id', width: 90 },
  { title: '账号', key: 'username', width: 140 },
  { title: '昵称', key: 'nickname', minWidth: 160, ellipsis: { tooltip: true }, render: row => row.nickname || '—' },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => h(NTag, { type: row.status === 0 ? 'success' : 'error' }, { default: () => (row.status === 0 ? '正常' : '禁用') })
  },
  { title: '创建人ID', key: 'createdBy', width: 110, render: row => row.createdBy || '—' },
  { title: '创建时间', key: 'createTime', width: 170, render: row => row.createTime || '—' },
  {
    title: '操作',
    key: 'actions',
    width: 390,
    fixed: 'right',
    render: row =>
      h('div', { class: 'flex gap-8px' }, [
        h(NButton, { size: 'small', onClick: () => openDetail(row) }, { default: () => '详情' }),
        ...(canDelete.value ? [h(NButton, { size: 'small', disabled: !!busyId.value, onClick: () => openExperience(row) }, { default: () => '调整积分' })] : []),
        h(NButton, { size: 'small', disabled: !!busyId.value, onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(
          NPopconfirm,
          { onPositiveClick: () => changeStatus(row) },
          {
            trigger: () =>
              h(
                NButton,
                { size: 'small', type: row.status === 0 ? 'error' : 'primary', disabled: !!busyId.value, loading: busyId.value === row.id && busyAction.value === 'status' },
                { default: () => (row.status === 0 ? '禁用' : '启用') }
              ),
            default: () =>
              row.status === 0 ? '禁用后该账号将立即下线且无法登录，确定禁用？' : '确定启用该账号？'
          }
        ),
        ...(canDelete.value ? [h(NPopconfirm, { positiveText: '确认删除', negativeText: '取消', positiveButtonProps: { type: 'error' }, placement: 'top-end', onPositiveClick: () => deleteUser(row) }, {
          trigger: () => h(NButton, { size: 'small', type: 'error', disabled: !!busyId.value, loading: busyId.value === row.id && busyAction.value === 'delete' }, { default: () => '删除' }),
          default: () => h('div', { style: { maxWidth: '300px', whiteSpace: 'normal', lineHeight: '1.6' } }, `确定删除官方账号“${row.username}”？关联社区身份将一起删除，后台与APP登录均立即失效。帖子、评论和历史互动保留，身份显示为已注销用户。原登录账号可重新注册，本后台不提供恢复功能。`)
        })] : [])
      ])
  }
];

onMounted(load);
</script>

<template>
  <NCard title="官方账号管理" :bordered="false" class="card-wrapper">
    <template #header-extra>
      <NButton type="primary" @click="openCreate">新建官方账号</NButton>
    </template>
    <NSpace class="mb-16px" align="center">
      <NInput v-model:value="keyword" clearable maxlength="100" placeholder="账号或昵称关键词" class="w-260px" @keyup.enter="search" />
      <NSelect v-model:value="statusFilter" :options="statusOptions" class="w-140px" @update:value="search" />
      <NButton type="primary" :loading="loading" @click="search">查询</NButton>
      <NButton @click="reset">重置</NButton>
    </NSpace>
    <NAlert v-if="errorMessage" type="error" class="mb-16px">
      {{ errorMessage }}
      <NButton text @click="load">重试</NButton>
    </NAlert>
    <NDataTable
      :columns="columns"
      :data="rows"
      :loading="loading"
      :row-key="(row: Api.AdminUser.ListItem) => row.id"
      :scroll-x="1220"
    />
    <div class="mt-16px flex justify-end">
      <NPagination
        v-model:page="query.pageNum"
        v-model:page-size="query.pageSize"
        :item-count="total"
        :page-sizes="[10, 20, 50, 100]"
        show-size-picker
        @update:page="load"
        @update:page-size="query.pageNum = 1; load()"
      />
    </div>

    <NModal v-model:show="createVisible" :mask-closable="!creating" :close-on-esc="!creating" :closable="!creating" preset="card" title="新建官方账号" class="w-520px max-w-95vw">
      <NAlert type="info" class="mb-16px">请填写完整账号（4-20位字母、数字或下划线，允许纯数字）；该账号可登录后台（只读、不能操作），也可用「账号 + 这里的密码」登录 APP（次元町）。</NAlert>
      <NForm :disabled="creating" label-placement="left" label-width="90">
        <NFormItem label="登录账号">
          <NInput v-model:value="createForm.account" maxlength="20" placeholder="请输入完整自定义账号" />
        </NFormItem>
        <NFormItem label="昵称（选填）">
          <NInput v-model:value="createForm.nickname" maxlength="20" placeholder="展示名，如：官方运营-张三" />
        </NFormItem>
        <NFormItem label="初始密码">
          <NInput v-model:value="createForm.password" type="password" show-password-on="click" placeholder="8-20位" />
        </NFormItem>
        <NFormItem label="确认密码">
          <NInput v-model:value="createForm.confirm" type="password" show-password-on="click" placeholder="再次输入初始密码" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="creating" @click="createVisible = false">取消</NButton>
          <NButton type="primary" :loading="creating" @click="submitCreate">创建</NButton>
        </NSpace>
      </template>
    </NModal>

    <UserEditModal v-model:show="editVisible" kind="official" :user="selected" @saved="load" />
    <UserDetailModal v-model:show="detailVisible" kind="official" :id="selected?.id || ''" />
    <ExperienceAdjustModal v-model:show="experienceVisible" kind="official" :user="selected" @saved="load" />
  </NCard>
</template>
