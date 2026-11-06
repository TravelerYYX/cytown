<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { fetchAppUserDetail, fetchOfficialAccountDetail } from '@/service/api';
import { useWindowSize } from '@vueuse/core';
import { mediaUrl } from '@/utils/service';

const props = defineProps<{ show: boolean; kind: 'app' | 'official'; id: string }>();
const emit = defineEmits<{ 'update:show': [value: boolean] }>();
const loading = ref(false);
const failed = ref(false);
const app = ref<Api.AppUser.Detail | null>(null);
const official = ref<Api.AdminUser.Detail | null>(null);
const { width } = useWindowSize();
const descriptionColumns = computed(() => width.value < 640 ? 1 : 2);
let requestId = 0;
async function load() {
  const current = ++requestId;
  app.value = null; official.value = null; failed.value = false;
  if (!props.show || !props.id) { loading.value = false; return; }
  loading.value = true;
  try {
    if (props.kind === 'app') {
      const { data, error } = await fetchAppUserDetail(props.id);
      if (current !== requestId) return;
      app.value = error ? null : data; failed.value = !app.value;
    } else {
      const { data, error } = await fetchOfficialAccountDetail(props.id);
      if (current !== requestId) return;
      official.value = error ? null : data; failed.value = !official.value;
    }
  } catch { if (current === requestId) failed.value = true; }
  finally { if (current === requestId) loading.value = false; }
}
watch(() => [props.show, props.kind, props.id], load, { immediate: true });
const community = computed(() => app.value || official.value?.community);
const summary = computed(() => app.value || official.value);
const avatar = computed(() => {
  const value = summary.value?.avatar;
  return value ? (/^https?:\/\//.test(value) ? value : mediaUrl(value)) : undefined;
});
const stats = computed(() => {
  const s = summary.value?.statistics;
  return [
    ['已发布帖子', s?.postCount || 0], ['粉丝', s?.followerCount || 0], ['关注', s?.followingCount || 0],
    ['获赞', s?.receivedLikeCount || 0], ['收藏帖子', s?.favoriteCount || 0]
  ];
});
const gender = computed(() => community.value?.gender === 0 ? '男' : community.value?.gender === 1 ? '女' : '保密');
</script>

<template>
  <NModal :show="show" preset="card" title="用户详情" class="w-780px max-w-95vw" @update:show="emit('update:show', $event)">
    <NSpin :show="loading" class="detail-scroll">
      <div v-if="loading" class="h-120px" />
      <NAlert v-else-if="failed" type="error">用户详情加载失败 <NButton text @click="load">重试</NButton></NAlert>
      <template v-else-if="summary">
        <NSpace align="center" class="mb-20px">
          <NAvatar round :size="56" :src="avatar">{{ summary.nickname?.slice(0, 1) || '?' }}</NAvatar>
          <div>
            <div class="text-18px font-medium">{{ summary.nickname || '未设置昵称' }}</div>
            <NText depth="3">{{ app?.account || official?.username }}</NText>
          </div>
          <NTag :type="summary.status === 0 ? 'success' : 'error'">{{ summary.status === 0 ? '正常' : '禁用' }}</NTag>
        </NSpace>
        <NDescriptions bordered :column="descriptionColumns" label-placement="left">
          <NDescriptionsItem label="ID">{{ summary.id }}</NDescriptionsItem>
          <NDescriptionsItem label="密码">{{ summary.hasPassword ? '已设置' : '未设置' }}</NDescriptionsItem>
          <NDescriptionsItem label="注册 / 创建时间">{{ summary.createTime || '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="更新时间">{{ summary.updateTime || '—' }}</NDescriptionsItem>
          <template v-if="official">
            <NDescriptionsItem label="账号类型">官方只读账号</NDescriptionsItem>
            <NDescriptionsItem label="创建人">{{ official.createdByName || '—' }}（ID：{{ official.createdBy || '—' }}）</NDescriptionsItem>
          </template>
        </NDescriptions>
        <NDivider>社区资料</NDivider>
        <NDescriptions v-if="community" bordered :column="descriptionColumns" label-placement="left">
          <NDescriptionsItem label="社区用户 ID">{{ community.id }}</NDescriptionsItem>
          <NDescriptionsItem label="公开 UID">{{ community.publicUid || '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="QQ号">{{ community.qqNumber || '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="邮箱">{{ community.email || '—' }}</NDescriptionsItem>
          <NDescriptionsItem v-if="official" label="社区注册时间">{{ community.createTime || '—' }}</NDescriptionsItem>
          <NDescriptionsItem v-if="official" label="社区更新时间">{{ community.updateTime || '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="社区昵称">{{ community.nickname || '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="关联官方账号 ID">{{ community.officialAdminId || '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="性别">{{ gender }}</NDescriptionsItem>
          <NDescriptionsItem label="等级">{{ community.level ?? '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="积分">{{ community.experience ?? '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="社区状态">{{ community.status === 0 ? '正常' : '禁用' }}</NDescriptionsItem>
          <NDescriptionsItem label="最后登录">{{ community.lastLoginTime || '尚未登录' }}</NDescriptionsItem>
          <NDescriptionsItem label="卡片背景">{{ community.cardBackground || '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="签名">{{ community.signature || '—' }}</NDescriptionsItem>
          <NDescriptionsItem label="标签" :span="descriptionColumns">
            <NSpace v-if="community.tags.length" :size="4"><NTag v-for="(tag, index) in community.tags" :key="index" size="small">{{ tag.name }}</NTag></NSpace>
            <span v-else>—</span>
          </NDescriptionsItem>
        </NDescriptions>
        <NEmpty v-else description="该官方账号尚未登录 APP，未建立社区身份" />
        <NDivider>业务统计</NDivider>
        <div class="grid grid-cols-2 gap-12px sm:grid-cols-5">
          <NStatistic v-for="item in stats" :key="item[0]" :label="String(item[0])" :value="Number(item[1])" />
        </div>
        <NText depth="3" class="mt-12px block">发帖、获赞及收藏仅统计已发布帖子。</NText>
      </template>
    </NSpin>
  </NModal>
</template>

<style scoped>
.detail-scroll { max-height: 75vh; overflow-y: auto; overflow-wrap: anywhere; }
</style>
