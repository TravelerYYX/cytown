<script setup lang="ts">
import { computed } from 'vue';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { mediaUrl } from '@/utils/service';
import { $t } from '@/locales';
import defaultAvatar from '@/assets/imgs/soybean.jpg';

defineOptions({
  name: 'HeaderBanner'
});

const appStore = useAppStore();
const authStore = useAuthStore();

const gap = computed(() => (appStore.isMobile ? 0 : 16));

/** 用户头像：优先显示上传的头像，无则显示默认图 */
const avatarUrl = computed(() =>
  authStore.userInfo.avatar ? mediaUrl(authStore.userInfo.avatar) : defaultAvatar
);

/** 展示名称：优先昵称，无昵称回退用户名 */
const displayName = computed(() => authStore.userInfo.nickName || authStore.userInfo.userName);

/** 按当前时段返回问候词条 key */
function getGreetingKey() {
  const hour = new Date().getHours();
  if (hour >= 5 && hour < 11) return 'page.home.greetingMorning';
  if (hour < 13) return 'page.home.greetingNoon';
  if (hour < 18) return 'page.home.greetingAfternoon';
  if (hour < 23) return 'page.home.greetingEvening';
  return 'page.home.greetingNight';
}

const greeting = computed(() => $t(getGreetingKey()));

interface StatisticData {
  id: number;
  label: string;
  value: string;
}

const statisticData = computed<StatisticData[]>(() => [
  {
    id: 0,
    label: $t('page.home.projectCount'),
    value: '25'
  },
  {
    id: 1,
    label: $t('page.home.todo'),
    value: '4/16'
  },
  {
    id: 2,
    label: $t('page.home.message'),
    value: '12'
  }
]);
</script>

<template>
  <NCard :bordered="false" class="card-wrapper">
    <NGrid :x-gap="gap" :y-gap="16" responsive="screen" item-responsive>
      <NGi span="24 s:24 m:18">
        <div class="flex-y-center">
          <div class="size-72px shrink-0 overflow-hidden rd-1/2">
            <img :src="avatarUrl" class="size-full object-cover" alt="用户头像" />
          </div>
          <div class="pl-12px">
            <h3 class="text-18px font-semibold">
              {{ $t('page.home.greeting', { greeting, userName: displayName }) }}
            </h3>
          </div>
        </div>
      </NGi>
      <NGi span="24 s:24 m:6">
        <NSpace :size="24" justify="end">
          <NStatistic v-for="item in statisticData" :key="item.id" class="whitespace-nowrap" v-bind="item" />
        </NSpace>
      </NGi>
    </NGrid>
  </NCard>
</template>

<style scoped></style>
