<script setup lang="ts">
import { h, onMounted,onUnmounted, reactive, ref,watch } from 'vue';
import PostPhonePreview from './modules/post-phone-preview.vue';
import { NButton, NPopconfirm, NSpace, NSwitch, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { fetchRecommendPost, fetchDeletePost, fetchPostDetail, fetchPostList, fetchUpdatePostHomeNews, fetchUpdatePostStatus } from '@/service/api';

defineOptions({ name: 'PostManage' });
const filters = reactive({ status: null as number | null, keyword: '' });
const query = reactive<Api.Post.ManageQueryParam>({ pageNum: 1, pageSize: 10 });
const rows = ref<Api.Post.PostList[]>([]);
const total = ref(0);
const loading = ref(false);
const errorMessage = ref('');
const busyId = ref('');
const detail = ref<Api.Post.PostDetail>();
const detailOpen = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const previewOpen=ref(false),previewId=ref('');
let detailVersion=0,detailSyncBusy=false,detailDeleted=false;
function showPreview(id:string){previewId.value=id;previewOpen.value=true;}
function failureMessage(value:unknown){const error=value as {response?:{data?:{msg?:string}};message?:string};return error?.response?.data?.msg||error?.message||'详情同步失败，请重试';}
async function syncDetail(){
  if(!detailOpen.value||!selectedId||detailSyncBusy||detailDeleted||document.hidden)return;
  const version=detailVersion,id=selectedId;detailSyncBusy=true;
  try{const {data,error}=await fetchPostDetail(id);if(version!==detailVersion||!detailOpen.value)return;if(error||!data){detailError.value=failureMessage(error);detailDeleted=/不存在|已删除/.test(detailError.value);if(detailDeleted)detail.value=undefined;return;}detail.value=data;detailError.value='';}
  catch(error){if(version===detailVersion)detailError.value=failureMessage(error);}
  finally{detailSyncBusy=false;}
}
watch(detailOpen,open=>{if(!open){detailVersion++;detailDeleted=false;}});
let selectedId = '';
let requestId = 0;
const options = [{ label: '待审核', value: 0 }, { label: '已发布', value: 1 }, { label: '已下架', value: 2 }];
const statusNames = ['待审核', '已发布', '已下架'];
async function load() {
  const current = ++requestId;
  loading.value = true;
  errorMessage.value = '';
  try {
    const { data, error } = await fetchPostList({ ...query });
    if (current !== requestId) return;
    if (error || !data) { errorMessage.value = '帖子加载失败，请重试'; return; }
    rows.value = data.records;
    total.value = data.total;
    if (query.pageNum > 1 && !data.records.length) {
      query.pageNum = Math.max(1, Math.ceil(data.total / query.pageSize));
      await load();
    }
  } catch {
    if (current === requestId) errorMessage.value = '帖子加载失败，请重试';
  } finally {
    if (current === requestId) loading.value = false;
  }
}
function search() {
  query.status = filters.status ?? undefined;
  query.keyword = filters.keyword.trim() || undefined;
  query.pageNum = 1;
  void load();
}
function reset() { filters.status = null; filters.keyword = ''; search(); }
async function changeStatus(row: Api.Post.PostList, status: number) {
  if (busyId.value) return;
  busyId.value = row.id;
  try {
    const { error } = await fetchUpdatePostStatus(row.id, status);
    if (!error) { window.$message?.success('状态已更新'); await load(); }
  } finally { busyId.value = ''; }
}
async function changeHomeNews(row: Api.Post.PostList, value: boolean) {
  if (busyId.value) return;
  busyId.value = row.id;
  try {
    const { error } = await fetchUpdatePostHomeNews(row.id, value);
    if (!error) {
      row.homeNewsEnabled = value;
      if (detail.value?.id === row.id) detail.value.homeNewsEnabled = value;
      window.$message?.success('首页资讯轮播设置已更新');
      await load();
    }
  } finally { busyId.value = ''; }
}
async function recommend(row:Api.Post.PostList,value:boolean){if(busyId.value)return;busyId.value=row.id;try{const r=await fetchRecommendPost(row.id,value);if(!r.error){row.checkinRecommended=value;if(detail.value?.id===row.id)detail.value.checkinRecommended=value;window.$message?.success('签到推荐设置已更新');}}finally{busyId.value='';}}
async function remove(row: Api.Post.PostList) {
  if (busyId.value) return;
  busyId.value = row.id;
  try {
    const { error } = await fetchDeletePost(row.id);
    if (!error) { window.$message?.success('帖子已删除'); await load(); }
  } finally { busyId.value = ''; }
}
async function showDetail(id: string) {
  const version=++detailVersion;
  detailDeleted=false;
  selectedId = id;
  detail.value = undefined;
  detailError.value = '';
  detailOpen.value = true;
  detailLoading.value = true;
  try {
    const { data, error } = await fetchPostDetail(id);
    if (selectedId !== id||version!==detailVersion||!detailOpen.value) return;
    if (error || !data) detailError.value = failureMessage(error);
    else detail.value = data;
  } finally { if (version===detailVersion) detailLoading.value = false; }
}
const columns: DataTableColumns<Api.Post.PostList> = [
  { title: 'ID', key: 'id', width: 80 },
  { title: '标题', key: 'title', minWidth: 220, ellipsis: { tooltip: true } },
  { title: '作者昵称', key: 'authorNickname', width: 140 },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { type: row.status === 1 ? 'success' : row.status === 2 ? 'error' : 'warning' }, { default: () => statusNames[row.status] }) },
  { title: '首页资讯轮播', key: 'homeNewsEnabled', width: 150, render: row => h(NSwitch, { value: row.homeNewsEnabled, disabled: !!busyId.value, loading: busyId.value === row.id, 'onUpdate:value': (value: boolean) => changeHomeNews(row, value) }) },
  { title: '设为推荐', key:'checkinRecommended',width:120,render:row=>h(NSwitch,{value:row.checkinRecommended,disabled:!!busyId.value,'onUpdate:value':(value:boolean)=>recommend(row,value)}) },
  { title: '点赞数', key: 'likeCount', width: 100 },
  { title: '评论数', key: 'commentCount', width: 100 },
  { title: '创建时间', key: 'createTime', width: 180 },
  { title: '操作', key: 'actions', width: 320, fixed: 'right', render: row => h(NSpace, { size: 8 }, { default: () => [
    h(NButton, { size: 'small', onClick: () => showDetail(row.id) }, { default: () => '详情' }),
    h(NButton, { size: 'small',type:'primary', onClick: () => showPreview(row.id) }, { default: () => '查看帖子' }),
    h(NButton, { size: 'small', disabled: !!busyId.value, loading: busyId.value === row.id, onClick: () => changeStatus(row, row.status === 1 ? 2 : 1) }, { default: () => row.status === 1 ? '下架' : '发布' }),
    h(NPopconfirm, { onPositiveClick: () => remove(row) }, {
      trigger: () => h(NButton, { size: 'small', type: 'error', disabled: !!busyId.value }, { default: () => '删除' }),
      default: () => '确定永久删除帖子及其全部评论、媒体和标签？'
    })
  ] }) }
];
let detailTimer:ReturnType<typeof setInterval>|undefined;
function resumeDetail(){if(!document.hidden)void syncDetail();}
onMounted(()=>{void load();detailTimer=setInterval(()=>{if(!detailError.value)void syncDetail();},3000);document.addEventListener('visibilitychange',resumeDetail);window.addEventListener('online',resumeDetail);});
onUnmounted(()=>{detailVersion++;clearInterval(detailTimer);document.removeEventListener('visibilitychange',resumeDetail);window.removeEventListener('online',resumeDetail);});
</script>

<template>
  <NCard title="帖子管理" :bordered="false" class="card-wrapper">
    <NSpace class="mb-16px" align="center">
      <NSelect v-model:value="filters.status" :options="options" clearable placeholder="全部状态" class="w-140px" />
      <NInput v-model:value="filters.keyword" clearable maxlength="100" placeholder="标题或正文关键词" class="w-240px" @keyup.enter="search" />
      <NButton type="primary" :loading="loading" @click="search">查询</NButton>
      <NButton @click="reset">重置</NButton>
    </NSpace>
    <NAlert v-if="errorMessage" type="error" class="mb-16px">
      {{ errorMessage }} <NButton text @click="load">重试</NButton>
    </NAlert>
    <NDataTable :columns="columns" :data="rows" :loading="loading" :row-key="(row: Api.Post.PostList) => row.id" :scroll-x="1400" />
    <div class="mt-16px flex justify-end">
      <NPagination v-model:page="query.pageNum" v-model:page-size="query.pageSize" :item-count="total" :page-sizes="[10, 20, 50, 100]" show-size-picker
        @update:page="load" @update:page-size="query.pageNum = 1; load()" />
    </div>
    <NModal v-model:show="detailOpen" preset="card" title="帖子详情" class="w-700px max-w-95vw">
      <NSpin :show="detailLoading">
        <NAlert v-if="detailError" type="error">{{ detailError }} <NButton text @click="showDetail(selectedId)">重试</NButton></NAlert>
        <template v-if="detail">
          <h2>{{ detail.title }}</h2>
          <p>首页资讯轮播：{{ detail.homeNewsEnabled ? '已开启（仅已发布帖子展示）' : '未开启' }}</p>
          <p>{{ detail.authorNickname }} · {{ detail.createTime }}</p>
          <p>类型：{{ detail.mode || '旧帖子' }} · 频道：{{ detail.channelName || '未归类' }} · 作者UID：{{ detail.authorPublicUid || '—' }}</p>
          <p>创作设置：{{ detail.original ? '原创' : '非原创' }} / {{ detail.allowDownload ? '允许下载' : '禁止下载' }} / {{ detail.downloadWatermark ? '下载加水印' : '无水印' }}</p>
          <p class="whitespace-pre-wrap break-words">{{ detail.content }}</p>
          <NCode v-if="detail.contentBlocks" :code="JSON.stringify(detail.contentBlocks, null, 2)" language="json" word-wrap />
          <p v-if="detail.authorBio">{{ detail.authorBio }}</p>
          <p>{{ detail.tags.join(' / ') }}</p>
          <p>媒体 {{ detail.media.length }} 项 · 点赞 {{ detail.likeCount }} · 评论 {{ detail.commentCount }} · 收藏 {{ detail.favoriteCount }} · 分享 {{ detail.shareCount }}</p>
          <p v-if="detail.collection">{{ detail.collection }}</p>
          <p v-if="detail.permission">{{ detail.permission }}</p>
          <NCode v-if="detail.poll" :code="JSON.stringify(detail.poll, null, 2)" language="json" word-wrap />
        </template>
      </NSpin>
    </NModal>
    <NDrawer v-model:show="previewOpen" :width="450" placement="right"><NDrawerContent title="查看帖子" closable><PostPhonePreview v-if="previewOpen" :key="previewId" :post-id="previewId"/></NDrawerContent></NDrawer>
  </NCard>
</template>
