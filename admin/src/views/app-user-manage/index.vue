<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import { NAvatar, NButton, NPopconfirm, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { fetchAppUserList, fetchCreateAppUser, fetchUpdateAppUserStatus, fetchDeleteAppUser } from '@/service/api';
import { mediaUrl } from '@/utils/service';
import { useAuthStore } from '@/store/modules/auth';
import OfficialAccountManage from './modules/official-account.vue';
import UserEditModal from './modules/user-edit-modal.vue';
import UserDetailModal from './modules/user-detail-modal.vue';
import ExperienceAdjustModal from './modules/experience-adjust-modal.vue';
import { validateAccount, validatePassword } from './modules/user-validation';
const editVisible = ref(false);
const detailVisible = ref(false);
const experienceVisible = ref(false);
const selected = ref<Api.AppUser.ListItem | null>(null);
function openEdit(row: Api.AppUser.ListItem) { selected.value = row; editVisible.value = true; }
function openDetail(row: Api.AppUser.ListItem) { selected.value = row; detailVisible.value = true; }
function openExperience(row: Api.AppUser.ListItem) { selected.value = row; experienceVisible.value = true; }

defineOptions({ name: 'AppUserManage' });

const authStore = useAuthStore();
const canDelete = computed(() => authStore.userInfo.userType === 0 && authStore.userInfo.roles.includes('R_SUPER'));
/** 用户管理页签：APP 用户 / 官方账号（官方账号仅超管可见，只读账号固定停留 APP 用户） */
const tab = ref<'app' | 'official'>('app');
watch(tab, value => { if (value === 'app') void load(); });

// ---------- APP 用户列表 ----------
const keyword = ref('');
const query = reactive<Api.AppUser.ManageQueryParam>({ pageNum: 1, pageSize: 10 });
const rows = ref<Api.AppUser.ListItem[]>([]);
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
    const { data, error } = await fetchAppUserList({ ...query });
    if (current !== requestId) return;
    if (error || !data) { errorMessage.value = '用户加载失败，请重试'; return; }
    rows.value = data.records; total.value = data.total;
    if (query.pageNum > 1 && !data.records.length) {
      query.pageNum = Math.max(1, Math.ceil(data.total / query.pageSize));
      await load();
    }
  } catch {
    if (current === requestId) errorMessage.value = '用户加载失败，请重试';
  } finally {
    if (current === requestId) loading.value = false;
  }
}
function search() { query.keyword = keyword.value.trim() || undefined; query.pageNum = 1; void load(); }
function reset() { keyword.value = ''; search(); }
async function changeStatus(row: Api.AppUser.ListItem) {
  if (busyId.value) return;
  busyId.value = row.id;
  busyAction.value = 'status';
  try {
    const { error } = await fetchUpdateAppUserStatus(row.id, row.status === 0 ? 1 : 0);
    if (!error) { window.$message?.success('用户状态已更新'); await load(); }
  } finally { busyId.value = ''; busyAction.value = ''; }
}
async function deleteUser(row: Api.AppUser.ListItem) {
  if (busyId.value || !canDelete.value) return;
  busyId.value = row.id;
  busyAction.value = 'delete';
  try {
    const { error } = await fetchDeleteAppUser(row.id);
    if (error) { window.$message?.error('删除失败，请重试'); return; }
    window.$message?.success('用户已删除');
    await load();
  } catch {
    window.$message?.error('删除失败，请重试');
  } finally { busyId.value = ''; busyAction.value = ''; }
}
const columns = computed<DataTableColumns<Api.AppUser.ListItem>>(() => {
  const base: DataTableColumns<Api.AppUser.ListItem> = [
    { title: 'ID', key: 'id', width: 90 },
    { title: '公开 UID', key: 'publicUid', width: 120, render: row => row.publicUid || '—' },
    { title: '登录账号', key: 'account', width: 160 },
    { title: 'QQ号', key: 'qqNumber', width: 150, render: row => row.qqNumber || '—' },
    { title: '昵称', key: 'nickname', minWidth: 160, ellipsis: { tooltip: true } },
    { title: '头像', key: 'avatar', width: 80, render: row => row.avatar
        ? h(NAvatar, { round: true, size: 36, src: mediaUrl(row.avatar), objectFit: 'cover' }, { fallback: () => row.nickname.slice(0, 1) })
        : h(NAvatar, { round: true, size: 36 }, { default: () => row.nickname.slice(0, 1) }) },
    { title: '状态', key: 'status', width: 100, render: row => h(NTag, { type: row.status === 0 ? 'success' : 'error' }, { default: () => row.status === 0 ? '正常' : '禁用' }) },
    { title: '最后登录', key: 'lastLoginTime', width: 170, render: row => row.lastLoginTime || '尚未登录' },
    { title: '注册时间', key: 'createTime', width: 170 }
  ];
  base.push({
    title: '操作', key: 'actions', width: authStore.isReadOnly ? 80 : 390, fixed: 'right',
    render: row => h('div', { class: 'flex gap-8px' }, [
      h(NButton, { size: 'small', onClick: () => openDetail(row) }, { default: () => '详情' }),
      ...(canDelete.value ? [h(NButton, { size: 'small', disabled: !!busyId.value, onClick: () => openExperience(row) }, { default: () => '调整积分' })] : []),
      ...(!authStore.isReadOnly ? [
        h(NButton, { size: 'small', disabled: !!busyId.value, onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(NPopconfirm, { onPositiveClick: () => changeStatus(row) }, {
          trigger: () => h(NButton, { size: 'small', type: row.status === 0 ? 'error' : 'primary', disabled: !!busyId.value, loading: busyId.value === row.id && busyAction.value === 'status' }, { default: () => row.status === 0 ? '禁用' : '启用' }),
          default: () => row.status === 0 ? '禁用后该用户将无法登录或继续操作，确定禁用？' : '确定启用该用户？'
        }),
        ...(canDelete.value ? [h(NPopconfirm, { positiveText: '确认删除', negativeText: '取消', positiveButtonProps: { type: 'error' }, placement: 'top-end', onPositiveClick: () => deleteUser(row) }, {
          trigger: () => h(NButton, { size: 'small', type: 'error', disabled: !!busyId.value, loading: busyId.value === row.id && busyAction.value === 'delete' }, { default: () => '删除' }),
          default: () => h('div', { style: { maxWidth: '300px', whiteSpace: 'normal', lineHeight: '1.6' } }, `确定删除账号“${row.account}”？${row.officialAdminId ? '关联官方账号及社区身份将一起删除。' : ''}账号将立即下线，帖子、评论和历史互动保留，身份显示为已注销用户。原登录账号和QQ号可重新注册，本后台不提供恢复功能。`)
        })] : [])
      ] : [])
    ])
  });
  return base;
});

// ---------- 新建 APP 用户 ----------
const createVisible = ref(false);
const creating = ref(false);
const createForm = reactive({ account: '', qqNumber: '', nickname: '', password: '', confirm: '' });

watch(createVisible, visible => { if (!visible) { createForm.password = ''; createForm.confirm = ''; } });

function openCreate() {
  createForm.account = '';
  createForm.qqNumber = '';
  createForm.nickname = '';
  createForm.password = '';
  createForm.confirm = '';
  createVisible.value = true;
}

async function submitCreate() {
  if (creating.value) return;
  if (createForm.account.trim()) {
    const invalid = validateAccount(createForm.account);
    if (invalid) { window.$message?.warning(invalid); return; }
  }
  const qq = createForm.qqNumber.trim();
  if (!/^[0-9]{5,11}$/.test(qq)) { window.$message?.warning('QQ号格式不正确（5-11位数字）'); return; }
  if (createForm.password) {
    const invalid = validatePassword(createForm.password);
    if (invalid) { window.$message?.warning(invalid); return; }
    if (createForm.password !== createForm.confirm) { window.$message?.warning('两次输入的密码不一致'); return; }
  }
  creating.value = true;
  try {
    const { data, error } = await fetchCreateAppUser({
      qqNumber: qq,
      account: createForm.account.trim() || undefined,
      nickname: createForm.nickname.trim() || undefined,
      password: createForm.password || undefined
    });
    if (!error && data) {
      window.$message?.success(`用户已创建：${data.account}${createForm.password ? '，初始密码已生效' : '（未设置密码，仅验证码登录）'}`);
      createVisible.value = false;
      await load();
    }
  } finally { creating.value = false; }
}

onMounted(load);
</script>

<template>
  <div>
    <NTabs v-if="!authStore.isReadOnly" v-model:value="tab" type="segment" class="mb-16px">
      <NTabPane name="app" tab="APP 用户" />
      <NTabPane name="official" tab="官方账号" />
    </NTabs>

    <NCard v-if="tab === 'app'" title="用户管理" :bordered="false" class="card-wrapper">
      <template #header-extra>
        <NButton v-if="!authStore.isReadOnly" type="primary" @click="openCreate">新建用户</NButton>
      </template>
      <NSpace class="mb-16px" align="center">
        <NInput v-model:value="keyword" clearable maxlength="100" placeholder="登录账号、QQ号或昵称关键词" class="w-260px" @keyup.enter="search" />
        <NButton type="primary" :loading="loading" @click="search">查询</NButton>
        <NButton @click="reset">重置</NButton>
      </NSpace>
      <NAlert v-if="errorMessage" type="error" class="mb-16px">
        {{ errorMessage }} <NButton text @click="load">重试</NButton>
      </NAlert>
      <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(row: Api.AppUser.ListItem) => row.id" :scroll-x="1570" />
      <div class="mt-16px flex justify-end">
        <NPagination v-model:page="query.pageNum" v-model:page-size="query.pageSize" :item-count="total" :page-sizes="[10, 20, 50, 100]" show-size-picker
          @update:page="load" @update:page-size="query.pageNum = 1; load()" />
      </div>

      <NModal v-model:show="createVisible" :mask-closable="!creating" :close-on-esc="!creating" :closable="!creating" preset="card" title="新建用户" class="w-520px max-w-95vw">
        <NAlert type="info" class="mb-16px">
          创建后用户可用 QQ 号通过邮箱验证码登录；若设置初始密码，可用「登录账号 + 密码」登录。自定义账号留空时使用 QQ 号作为登录账号。
        </NAlert>
        <NForm :disabled="creating" label-placement="left" label-width="110">
          <NFormItem label="账号（选填）">
            <NInput v-model:value="createForm.account" maxlength="20" placeholder="4-20位字母、数字或下划线，留空使用QQ号" />
          </NFormItem>
          <NFormItem label="QQ号">
            <NInput v-model:value="createForm.qqNumber" maxlength="11" placeholder="5-11位数字" />
          </NFormItem>
          <NFormItem label="昵称（选填）">
            <NInput v-model:value="createForm.nickname" maxlength="20" placeholder="默认为：町酱+QQ号" />
          </NFormItem>
          <NFormItem label="初始密码（选填）">
            <NInput v-model:value="createForm.password" type="password" show-password-on="click" placeholder="8-20位，留空则仅验证码登录" />
          </NFormItem>
          <NFormItem v-if="createForm.password" label="确认密码">
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
    </NCard>

    <OfficialAccountManage v-else />
    <UserEditModal v-model:show="editVisible" kind="app" :user="selected" @saved="load" />
    <UserDetailModal v-model:show="detailVisible" kind="app" :id="selected?.id || ''" />
    <ExperienceAdjustModal v-model:show="experienceVisible" kind="app" :user="selected" @saved="load" />
  </div>
</template>
