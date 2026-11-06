<script setup lang="ts">
import { computed } from 'vue';
import type { VNode } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useSvgIcon } from '@/hooks/common/icon';
import { mediaUrl } from '@/utils/service';
import { $t } from '@/locales';

defineOptions({
  name: 'UserAvatar'
});

const authStore = useAuthStore();
const { routerPushByKey, toLogin } = useRouterPush();
const { SvgIconVNode } = useSvgIcon();

/** 用户头像：优先显示上传的头像，无则回退默认图 */
const avatarUrl = computed(() =>
  authStore.userInfo.avatar ? mediaUrl(authStore.userInfo.avatar) : null
);

/** 展示名称：优先昵称，无昵称回退用户名 */
const displayName = computed(() => authStore.userInfo.nickName || authStore.userInfo.userName);

function loginOrRegister() {
  toLogin();
}

type DropdownKey = 'logout';

type DropdownOption =
  | {
      key: DropdownKey;
      label: string;
      icon?: () => VNode;
    }
  | {
      type: 'divider';
      key: string;
    };

const options = computed(() => {
  const opts: DropdownOption[] = [
    {
      label: $t('common.logout'),
      key: 'logout',
      icon: SvgIconVNode({ icon: 'ph:sign-out', fontSize: 18 })
    }
  ];

  return opts;
});

function logout() {
  window.$dialog?.info({
    title: $t('common.tip'),
    content: $t('common.logoutConfirm'),
    positiveText: $t('common.confirm'),
    negativeText: $t('common.cancel'),
    onPositiveClick: () => {
      authStore.resetStore();
    }
  });
}

function handleDropdown(key: DropdownKey) {
  if (key === 'logout') {
    logout();
  } else {
    // If your other options are jumps from other routes, they will be directly supported here
    routerPushByKey(key);
  }
}
</script>

<template>
  <NButton v-if="!authStore.isLogin" quaternary @click="loginOrRegister">
    {{ $t('page.login.common.loginOrRegister') }}
  </NButton>
  <NDropdown v-else placement="bottom" trigger="click" :options="options" @select="handleDropdown">
    <div>
      <ButtonIcon>
        <template v-if="avatarUrl">
          <NAvatar round :size="26" :src="avatarUrl" />
        </template>
        <SvgIcon v-else icon="ph:user-circle" class="text-icon-large" />
        <span class="text-16px font-medium">{{ displayName }}</span>
        <NTag v-if="authStore.isReadOnly" size="small" type="warning" :bordered="false">只读账号</NTag>
      </ButtonIcon>
    </div>
  </NDropdown>
</template>

<style scoped></style>
