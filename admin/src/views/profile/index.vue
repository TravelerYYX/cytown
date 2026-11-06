<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import type { FormInst, FormRules, UploadCustomRequestOptions } from 'naive-ui';
import { NTag } from 'naive-ui';
import { fetchUpdateAvatar, fetchUpdateNickname, fetchUpdatePassword, fetchUpdateUserName } from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';
import { mediaUrl } from '@/utils/service';
import defaultAvatar from '@/assets/imgs/soybean.jpg';

defineOptions({ name: 'Profile' });

const authStore = useAuthStore();

/** ===== 头像设置 ===== */
const AVATAR_MAX_SIZE = 2 * 1024 * 1024;
const avatarUploading = ref(false);

const avatarUrl = computed(() =>
  authStore.userInfo.avatar ? mediaUrl(authStore.userInfo.avatar) : defaultAvatar
);

async function handleUploadAvatar({ file, onFinish, onError }: UploadCustomRequestOptions) {
  if (!file.file) return;
  if (file.file.size > AVATAR_MAX_SIZE) {
    window.$message?.error('头像大小不能超过 2MB');
    onError();
    return;
  }
  avatarUploading.value = true;
  const { error } = await fetchUpdateAvatar(file.file);
  avatarUploading.value = false;
  if (!error) {
    window.$message?.success('头像上传成功');
    // 重新拉取用户信息，同步首页与顶部导航显示
    await authStore.initUserInfo();
    onFinish();
  } else {
    onError();
  }
}

/** ===== 修改昵称 ===== */
const nickNameFormRef = ref<FormInst | null>(null);
const nickNameModel = reactive({
  nickname: authStore.userInfo.nickName
});
const nickNameRules: FormRules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 20, message: '昵称长度需在 2-20 个字符之间', trigger: 'blur' }
  ]
};
const nickNameSubmitting = ref(false);

async function handleSubmitNickName(e: MouseEvent) {
  e.preventDefault();
  await nickNameFormRef.value?.validate();
  nickNameSubmitting.value = true;
  const { error } = await fetchUpdateNickname(nickNameModel.nickname);
  nickNameSubmitting.value = false;
  if (!error) {
    window.$message?.success('昵称修改成功');
    // 重新拉取用户信息，更新首页与顶部导航显示
    await authStore.initUserInfo();
  }
}

/** ===== 修改用户名 ===== */
const userNameFormRef = ref<FormInst | null>(null);
const userNameModel = reactive({
  userName: authStore.userInfo.userName
});
const userNameRules: FormRules = {
  userName: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 50, message: '用户名长度需在 2-50 个字符之间', trigger: 'blur' }
  ]
};
const userNameSubmitting = ref(false);

async function handleSubmitUserName(e: MouseEvent) {
  e.preventDefault();
  await userNameFormRef.value?.validate();
  userNameSubmitting.value = true;
  const { error } = await fetchUpdateUserName(userNameModel.userName);
  userNameSubmitting.value = false;
  if (!error) {
    window.$message?.success('用户名修改成功');
    // 重新拉取用户信息，更新顶部导航显示
    await authStore.initUserInfo();
  }
}

/** ===== 修改密码 ===== */
const passwordFormRef = ref<FormInst | null>(null);
const passwordModel = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
});
function validateConfirmPassword(_rule: FormRules[string], value: string) {
  if (!value) {
    return new Error('请再次输入新密码');
  }
  if (value !== passwordModel.newPassword) {
    return new Error('两次输入的密码不一致');
  }
  return true;
}
const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '新密码长度需在 6-32 个字符之间', trigger: 'blur' }
  ],
  confirmPassword: [{ validator: validateConfirmPassword, trigger: 'blur' }]
};
const passwordSubmitting = ref(false);

async function handleSubmitPassword(e: MouseEvent) {
  e.preventDefault();
  await passwordFormRef.value?.validate();
  passwordSubmitting.value = true;
  const { error } = await fetchUpdatePassword(passwordModel.oldPassword, passwordModel.newPassword);
  passwordSubmitting.value = false;
  if (!error) {
    window.$message?.success('密码修改成功，即将跳转登录页');
    // 密码已变更，强制重新登录
    setTimeout(async () => {
      await authStore.resetStore();
    }, 1000);
  }
}
</script>

<template>
  <div class="profile-page">
    <!-- 头像设置 -->
    <NCard :bordered="false" class="card-wrapper account-card">
      <template #header>
        <div class="card-header">
          <span class="accent-bar"></span>
          <SvgIcon icon="ant-design:camera-outlined" class="header-icon" />
          <span>头像设置</span>
        </div>
      </template>

      <div class="account-body">
        <div class="avatar-ring">
          <img :src="avatarUrl" class="avatar-img" alt="当前头像" />
        </div>

        <div class="account-info">
          <div class="account-name-row">
            <span class="account-name">{{ authStore.userInfo.nickName || authStore.userInfo.userName }}</span>
            <NTag
              v-for="role in authStore.userInfo.roles"
              :key="role"
              size="small"
              type="primary"
              :bordered="false"
              round
            >
              {{ role }}
            </NTag>
          </div>
          <div class="account-meta">
            <SvgIcon icon="ant-design:user-outlined" class="meta-icon" />
            <span>用户名：{{ authStore.userInfo.userName }}</span>
          </div>
          <div class="account-meta">
            <SvgIcon icon="ant-design:idcard-outlined" class="meta-icon" />
            <span>用户ID：{{ authStore.userInfo.userId }}</span>
          </div>
        </div>

        <div class="account-upload">
          <NUpload
            accept="image/png,image/jpeg,image/gif,image/webp"
            :custom-request="handleUploadAvatar"
            :show-file-list="false"
          >
            <NButton type="primary" :loading="avatarUploading">
              <template #icon>
                <SvgIcon icon="ant-design:cloud-upload-outlined" />
              </template>
              上传新头像
            </NButton>
          </NUpload>
          <div class="upload-tip">支持 jpg/png/gif/webp 格式，大小不超过 2MB</div>
        </div>
      </div>
    </NCard>

    <!-- 基本信息与账号安全 -->
    <NGrid cols="s:1 m:2" responsive="screen" :x-gap="16" :y-gap="16" class="mt-16px">
      <NGi>
        <NSpace vertical :size="16" class="w-full">
          <!-- 修改昵称 -->
          <NCard :bordered="false" class="card-wrapper">
            <template #header>
              <div class="card-header">
                <span class="accent-bar"></span>
                <SvgIcon icon="ant-design:smile-outlined" class="header-icon" />
                <span>修改昵称</span>
              </div>
            </template>
            <div class="form-tip">当前用户名：{{ authStore.userInfo.userName }}</div>
            <NForm
              ref="nickNameFormRef"
              :model="nickNameModel"
              :rules="nickNameRules"
              :label-width="90"
              label-placement="left"
            >
              <NFormItem label="昵称" path="nickname">
                <NInput v-model:value="nickNameModel.nickname" placeholder="请输入新昵称" />
              </NFormItem>
              <NFormItem :show-label="false" :show-feedback="false">
                <div class="form-actions">
                  <NButton type="primary" :loading="nickNameSubmitting" @click="handleSubmitNickName">
                    保存昵称
                  </NButton>
                </div>
              </NFormItem>
            </NForm>
          </NCard>

          <!-- 修改用户名 -->
          <NCard :bordered="false" class="card-wrapper">
            <template #header>
              <div class="card-header">
                <span class="accent-bar"></span>
                <SvgIcon icon="ant-design:user-outlined" class="header-icon" />
                <span>修改用户名</span>
              </div>
            </template>
            <div class="form-tip">当前用户名：{{ authStore.userInfo.userName }}</div>
            <NForm
              ref="userNameFormRef"
              :model="userNameModel"
              :rules="userNameRules"
              :label-width="90"
              label-placement="left"
            >
              <NFormItem label="用户名" path="userName">
                <NInput v-model:value="userNameModel.userName" placeholder="请输入新用户名" />
              </NFormItem>
              <NFormItem :show-label="false" :show-feedback="false">
                <div class="form-actions">
                  <NButton type="primary" :loading="userNameSubmitting" @click="handleSubmitUserName">
                    保存用户名
                  </NButton>
                </div>
              </NFormItem>
            </NForm>
          </NCard>
        </NSpace>
      </NGi>

      <NGi>
        <div class="right-col">
          <!-- 修改密码 -->
          <NCard :bordered="false" class="card-wrapper">
            <template #header>
              <div class="card-header">
                <span class="accent-bar"></span>
                <SvgIcon icon="ant-design:lock-outlined" class="header-icon" />
                <span>修改密码</span>
              </div>
            </template>
            <NForm
              ref="passwordFormRef"
              :model="passwordModel"
              :rules="passwordRules"
              :label-width="90"
              label-placement="left"
            >
              <NFormItem label="旧密码" path="oldPassword">
                <NInput
                  v-model:value="passwordModel.oldPassword"
                  type="password"
                  show-password-on="click"
                  placeholder="请输入旧密码"
                />
              </NFormItem>
              <NFormItem label="新密码" path="newPassword">
                <NInput
                  v-model:value="passwordModel.newPassword"
                  type="password"
                  show-password-on="click"
                  placeholder="请输入新密码（6-32 位）"
                />
              </NFormItem>
              <NFormItem label="确认密码" path="confirmPassword">
                <NInput
                  v-model:value="passwordModel.confirmPassword"
                  type="password"
                  show-password-on="click"
                  placeholder="请再次输入新密码"
                />
              </NFormItem>
              <NFormItem :show-label="false" :show-feedback="false">
                <div class="form-actions">
                  <NButton type="primary" :loading="passwordSubmitting" @click="handleSubmitPassword">
                    修改密码
                  </NButton>
                </div>
              </NFormItem>
            </NForm>
          </NCard>

          <!-- 安全提示 -->
          <NAlert type="info" :bordered="false" title="安全提示" class="card-wrapper security-alert">
            <div class="security-item">
              <SvgIcon icon="ant-design:info-circle-outlined" class="security-icon" />
              密码长度需在 6-32 位之间
            </div>
            <div class="security-item">
              <SvgIcon icon="ant-design:sync-outlined" class="security-icon" />
              修改成功后需重新登录
            </div>
          </NAlert>
        </div>
      </NGi>
    </NGrid>
  </div>
</template>

<style scoped>
/* ===== 卡片标题 ===== */
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: rgb(var(--base-text-color));
}

.accent-bar {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: rgb(var(--primary-color));
  flex-shrink: 0;
}

.header-icon {
  font-size: 17px;
  color: rgb(var(--primary-color));
}

/* ===== 账号信息卡片 ===== */
.account-card {
  position: relative;
  overflow: hidden;
}

.account-card::after {
  content: '';
  position: absolute;
  top: -140px;
  right: -100px;
  width: 360px;
  height: 360px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(var(--primary-color), 0.12), transparent 68%);
  pointer-events: none;
}

.account-body {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
  text-align: center;
}

@media (min-width: 768px) {
  .account-body {
    flex-direction: row;
    align-items: center;
    gap: 32px;
    text-align: left;
  }
}

.avatar-ring {
  width: 104px;
  height: 104px;
  padding: 4px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgb(var(--primary-color)), rgba(var(--primary-color), 0.35));
  box-shadow: 0 6px 18px rgba(var(--primary-color), 0.22);
  flex-shrink: 0;
}

.avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
  display: block;
}

.account-info {
  flex: 1;
  min-width: 0;
}

.account-name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

@media (min-width: 768px) {
  .account-name-row {
    justify-content: flex-start;
  }
}

.account-name {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.4;
  color: rgb(var(--base-text-color));
}

.account-meta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 13px;
  color: rgba(var(--base-text-color), 0.6);
}

@media (min-width: 768px) {
  .account-meta {
    justify-content: flex-start;
  }
}

.meta-icon {
  font-size: 14px;
  color: rgba(var(--primary-color), 0.85);
}

.account-upload {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

@media (min-width: 768px) {
  .account-upload {
    margin-left: auto;
  }
}

.upload-tip {
  font-size: 12px;
  color: rgba(var(--base-text-color), 0.45);
}

/* ===== 表单 ===== */
.form-tip {
  margin-bottom: 14px;
  font-size: 13px;
  color: rgba(var(--base-text-color), 0.6);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  width: 100%;
}

/* ===== 右列：安全提示 ===== */
.right-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.security-item {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 13px;
  color: rgba(var(--base-text-color), 0.65);
}

.security-item:first-of-type {
  margin-top: 0;
}

.security-icon {
  font-size: 14px;
  color: rgb(var(--primary-color));
}
</style>
