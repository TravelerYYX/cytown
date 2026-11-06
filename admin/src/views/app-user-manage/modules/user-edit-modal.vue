<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { fetchUpdateAppUserAccount, fetchResetAppUserPassword, fetchOfficialAccountUpdateAccount, fetchOfficialAccountResetPassword } from '@/service/api';
import { validateAccount, validatePassword } from './user-validation';

const props = defineProps<{
  show: boolean;
  kind: 'app' | 'official';
  user: { id: string; account: string; officialAdminId?: string | null } | null;
}>();
const emit = defineEmits<{ 'update:show': [value: boolean]; saved: [] }>();
const tab = ref<'account' | 'password'>('account');
const saving = ref(false);
const form = reactive({ account: '', password: '', confirm: '' });
watch(() => [props.show, props.user?.id, props.kind], () => {
  form.password = ''; form.confirm = '';
  form.account = props.user?.account || '';
  tab.value = 'account';
});
function close(value: boolean) { if (!saving.value) emit('update:show', value); }
async function save() {
  if (saving.value || !props.user) return;
  const activeTab = tab.value;
  const invalid = activeTab === 'account' ? validateAccount(form.account) : validatePassword(form.password);
  if (invalid) { window.$message?.warning(invalid); return; }
  if (activeTab === 'password' && form.password !== form.confirm) { window.$message?.warning('两次输入的密码不一致'); return; }
  const id = props.user.id;
  saving.value = true;
  try {
    const { error } = activeTab === 'account'
      ? await (props.kind === 'app' ? fetchUpdateAppUserAccount(id, form.account.trim()) : fetchOfficialAccountUpdateAccount(id, form.account.trim()))
      : await (props.kind === 'app' ? fetchResetAppUserPassword(id, form.password) : fetchOfficialAccountResetPassword(id, form.password));
    if (!error) {
      form.password = ''; form.confirm = '';
      window.$message?.success(`${activeTab === 'account' ? '账号' : '密码'}已更新，用户需重新登录`);
      emit('update:show', false);
      emit('saved');
    }
  } finally { saving.value = false; }
}
</script>

<template>
  <NModal :show="show" preset="card" :title="`编辑用户：${user?.account || ''}`" class="w-520px max-w-95vw"
    :mask-closable="!saving" :close-on-esc="!saving" :closable="!saving" @update:show="close">
    <NTabs v-model:value="tab" type="line" class="mb-16px">
      <NTab name="account" :disabled="saving">修改账号</NTab>
      <NTab name="password" :disabled="saving">修改密码</NTab>
    </NTabs>
    <NAlert v-if="kind === 'official' || user?.officialAdminId" type="info" class="mb-16px">
      此用户关联官方账号，修改将同步影响后台和 APP 登录，社区资料保持关联。
    </NAlert>
    <NForm label-placement="left" label-width="90">
      <template v-if="tab === 'account'">
        <NFormItem label="登录账号">
          <NInput v-model:value="form.account" :disabled="saving" maxlength="20" placeholder="4-20位字母、数字或下划线" @keyup.enter="save" />
        </NFormItem>
        <NText depth="3">账号不区分大小写。修改后请使用新账号进行密码登录；普通用户的 QQ 验证码登录仍可用。</NText>
      </template>
      <template v-else>
        <NFormItem label="新密码">
          <NInput v-model:value="form.password" :disabled="saving" type="password" show-password-on="click" maxlength="20" placeholder="8-20位，不含空格" />
        </NFormItem>
        <NFormItem label="确认密码">
          <NInput v-model:value="form.confirm" :disabled="saving" type="password" show-password-on="click" maxlength="20" placeholder="再次输入新密码" @keyup.enter="save" />
        </NFormItem>
        <NText depth="3">管理员可直接设置或重置密码，无需旧密码。保存后该用户的旧登录将失效。</NText>
      </template>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton :disabled="saving" @click="close(false)">取消</NButton>
        <NButton type="primary" :loading="saving" @click="save">保存{{ tab === 'account' ? '账号' : '密码' }}</NButton>
      </NSpace>
    </template>
  </NModal>
</template>
