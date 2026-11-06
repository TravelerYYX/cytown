<script setup lang="ts">
import { onMounted,reactive,ref } from 'vue';
import ImageUpload from '@/components/image-upload/index.vue';
import CollapseCard from '@/components/custom/collapse-card/index.vue';
import DragSortList from '@/components/custom/drag-sort-list/index.vue';
import MineEntryManager from './modules/mine-entry-manager.vue';
import MembershipEntrySettings from './modules/membership-entry-settings.vue';
import EntryTargetEditor from './modules/entry-target-editor.vue';
import HeaderEntrySettings from './modules/header-entry-settings.vue';
import { validateEntryTarget } from './modules/entry-target';
import { createChannel,createEntry,deleteChannel,deleteEntry,fetchChannels,fetchEntries,fetchNewsConfig,fetchPublishHealth,rebuildPublishFeed,sortChannels,sortEntries,updateChannel,updateEntry,updateNewsConfig } from '@/service/api';
import { mediaUrl } from '@/utils/service';
import { fetchLayoutConfig, updateLayoutConfig } from '@/service/api';
defineOptions({name:'HomeConfig'});
const channels=ref<Api.P3.Channel[]>([]),entries=ref<Api.P3.Entry[]>([]),loading=ref(false),health=ref<Api.Publish.Health>();
const channelOpen=ref(false),entryOpen=ref(false);const editing=ref(false);
const channelForm=reactive<Partial<Api.P3.Channel>>({name:'',sortOrder:0,enabled:true,isDefault:false,adminOnly:false,showAllPosts:false,allowPublish:false});
const entryForm=reactive<Partial<Api.P3.Entry>>({name:'',iconUrl:'',targetType:'link',linkUrl:'',openMode:'inapp',showTopbar:true,sortOrder:0,enabled:true});
const news=reactive<Api.P3.NewsConfig>({enabled:true,interval:3,emptyText:'暂无资讯，敬请期待'});
const layout=reactive<Api.P3.LayoutConfig>({defaultFeedLayout:'single'});
const layoutLoading=ref(true),layoutError=ref(false),savingLayout=ref(false);
async function loadLayout(){
 layoutLoading.value=true;layoutError.value=false;
 try{const r=await fetchLayoutConfig();if(r.error||!r.data){layoutError.value=true;return;}Object.assign(layout,r.data);}
 catch{layoutError.value=true;}
 finally{layoutLoading.value=false;}
}
async function saveLayout(){
 if(savingLayout.value||layoutLoading.value||layoutError.value)return;
 savingLayout.value=true;
 try{const r=await updateLayoutConfig({...layout});if(!r.error){if(r.data)Object.assign(layout,r.data);window.$message?.success('默认帖子布局已保存');}}
 finally{savingLayout.value=false;}
}
async function load(){loading.value=true;try{const [c,e,n,h]=await Promise.all([fetchChannels(),fetchEntries(),fetchNewsConfig(),fetchPublishHealth()]);if(c.data)channels.value=c.data;if(e.data)entries.value=e.data;if(n.data)Object.assign(news,n.data);if(h.data)health.value=h.data;}finally{loading.value=false;}}
function openChannel(row?:Api.P3.Channel){editing.value=!!row;Object.assign(channelForm,{adminOnly:false,showAllPosts:false,allowPublish:true},row||{id:undefined,name:'',sortOrder:0,enabled:true,isDefault:false,adminOnly:false,showAllPosts:false,allowPublish:false});channelOpen.value=true;}
async function saveChannel(){if(!channelForm.name?.trim()){window.$message?.warning('请输入频道名称');return;}const r=editing.value?await updateChannel(channelForm):await createChannel(channelForm);if(!r.error){channelOpen.value=false;window.$message?.success('频道已保存');await load();}}
function openEntry(row?:Api.P3.Entry){editing.value=!!row;Object.assign(entryForm,{targetType:'link'},row||{id:undefined,name:'',iconUrl:'',targetType:'link',linkUrl:'',openMode:'inapp',showTopbar:true,sortOrder:0,enabled:true});entryOpen.value=true;}
async function saveEntry(){if(!entryForm.name?.trim()||!entryForm.iconUrl||!entryForm.linkUrl?.trim()){window.$message?.warning('请完整填写名称、图标和跳转地址');return;}if(!validateEntryTarget(entryForm.targetType,entryForm.linkUrl))return;const r=editing.value?await updateEntry(entryForm):await createEntry(entryForm);if(!r.error){entryOpen.value=false;window.$message?.success('入口已保存');await load();}}
async function quickChannel(row:Api.P3.Channel,value:boolean){await updateChannel({...row,enabled:value});await load();}
async function quickEntry(row:Api.P3.Entry,value:boolean){await updateEntry({...row,enabled:value});await load();}
async function removeChannel(row:Api.P3.Channel){await deleteChannel(row.id);await load();}
async function removeEntry(row:Api.P3.Entry){await deleteEntry(row.id);await load();}
async function saveChannelOrder(){const r=await sortChannels(channels.value.map(c=>c.id));if(!r.error){window.$message?.success('频道顺序已保存');await load();}}
async function saveEntryOrder(){const r=await sortEntries(entries.value.map(e=>e.id));if(!r.error){window.$message?.success('入口顺序已保存');await load();}}
const savingNews=ref(false);
async function saveNews(){
 if(savingNews.value)return;
 const emptyText=news.emptyText.trim();
 if(!emptyText||Array.from(emptyText).length>100){window.$message?.warning('无资讯时显示文案需为1-100个字符');return;}
 savingNews.value=true;
 try{
  const r=await updateNewsConfig({...news,emptyText});if(!r.error){if(r.data)Object.assign(news,r.data);window.$message?.success('资讯栏配置已保存');}}
 finally{savingNews.value=false;}
}
async function rebuildFeed(){const r=await rebuildPublishFeed();if(!r.error)window.$message?.success('已重建 '+(r.data||0)+' 条发布 Feed');}
onMounted(load);
onMounted(loadLayout);
</script>

<template>
  <div>
    <NSpace vertical :size="16">
      <HeaderEntrySettings />
      <CollapseCard title="快捷入口管理"><template #extra><NSpace align="center" :size="12"><span class="text-12px opacity-50">拖动手柄调整顺序</span><NButton type="primary" @click="openEntry()">新增入口</NButton></NSpace></template><NSpin :show="loading"><DragSortList v-model="entries" @reorder="saveEntryOrder"><template #header="{item}"><NSpace align="center" :size="8" class="min-w-0"><NImage :src="mediaUrl(item.iconUrl)" width="24" height="24" object-fit="cover" :show-toolbar="false" preview-disabled /><span class="font-medium truncate">{{ item.name }}</span><NTag v-if="!item.enabled" size="small" type="warning">已停用</NTag><span class="text-13px opacity-50 truncate" :title="item.linkUrl">{{ item.linkUrl }}</span><NTag size="small" :bordered="false">{{ item.targetType==='app'?'APP 页面':item.openMode==='inapp'?'网页 · 软件内':'网页 · 浏览器' }}</NTag></NSpace></template><template #actions="{item}"><NSwitch size="small" :value="item.enabled" @update:value="v=>quickEntry(item,v)" /><NButton size="small" @click="openEntry(item)">编辑</NButton><NPopconfirm @positive-click="removeEntry(item)"><template #trigger><NButton size="small" type="error">删除</NButton></template>确定删除该入口？</NPopconfirm></template></DragSortList></NSpin></CollapseCard>
      <CollapseCard title="频道管理"><template #extra><NSpace align="center" :size="12"><span class="text-12px opacity-50">拖动手柄调整顺序</span><NButton type="primary" @click="openChannel()">新增频道</NButton></NSpace></template><NSpin :show="loading"><DragSortList v-model="channels" @reorder="saveChannelOrder"><template #header="{item}"><NSpace align="center" :size="8" class="min-w-0"><span class="font-medium truncate">{{ item.name }}</span><NTag v-if="item.isDefault" size="small" type="info">默认</NTag><NTag v-if="item.showAllPosts" size="small" type="success">全部帖子</NTag><NTag v-if="item.allowPublish === false" size="small">禁止发布</NTag><NTag v-if="item.adminOnly" size="small" type="info">仅管理员发布</NTag><NTag v-if="!item.enabled" size="small" type="warning">已停用</NTag></NSpace></template><template #actions="{item}"><NSwitch size="small" :value="item.enabled" :disabled="item.isDefault" @update:value="v=>quickChannel(item,v)" /><NButton size="small" @click="openChannel(item)">编辑</NButton><NPopconfirm @positive-click="removeChannel(item)"><template #trigger><NButton size="small" type="error" :disabled="item.isDefault">删除</NButton></template>确定删除该频道？</NPopconfirm></template></DragSortList></NSpin></CollapseCard>
      <MineEntryManager />
      <MembershipEntrySettings />
      <CollapseCard title="默认帖子布局">
        <NSpin :show="layoutLoading">
          <NAlert v-if="layoutError" type="error" class="mb-16px">布局配置加载失败，请重试。<NButton size="small" @click="loadLayout">重试</NButton></NAlert>
          <NForm label-placement="left" label-width="180">
            <NFormItem label="首页默认帖子布局">
              <NRadioGroup v-model:value="layout.defaultFeedLayout" :disabled="layoutLoading || layoutError || savingLayout">
                <NSpace><NRadio value="single">单列布局</NRadio><NRadio value="waterfall">双列瀑布布局</NRadio></NSpace>
              </NRadioGroup>
            </NFormItem>
            <NAlert type="info" class="mb-16px">仅影响当前设备尚未手动选择首页帖子布局的用户。用户在设置中选择的布局优先；关注页和我的帖子不受影响。</NAlert>
            <NButton type="primary" :loading="savingLayout" :disabled="layoutLoading || layoutError" @click="saveLayout">保存默认帖子布局</NButton>
          </NForm>
        </NSpin>
      </CollapseCard>
      <CollapseCard title="资讯栏配置"><NForm label-placement="left" label-width="180"><NFormItem label="启用首页资讯栏"><NSwitch v-model:value="news.enabled" /></NFormItem><NFormItem label="轮播间隔（秒）"><NInputNumber v-model:value="news.interval" :min="2" :max="10" /></NFormItem><NFormItem label="无资讯时显示文案"><NInput v-model:value="news.emptyText" maxlength="100" show-count placeholder="暂无资讯，敬请期待" /></NFormItem><NAlert type="info" class="mb-16px">资讯内容来源：帖子管理中开启“首页资讯轮播”的已发布帖子，按创建时间倒序展示，不限制条数。没有符合条件的帖子时显示占位文案。</NAlert><NButton type="primary" :loading="savingNews" @click="saveNews">保存资讯栏配置</NButton></NForm></CollapseCard>
      <CollapseCard title="运行状态"><template #extra><NButton @click="rebuildFeed">重建 Redis Feed</NButton></template><NDescriptions v-if="health" bordered :column="1"><NDescriptionsItem label="Redis"><NTag :type="health.redis.available?'success':'error'">{{ health.redis.message }}</NTag></NDescriptionsItem><NDescriptionsItem label="FFmpeg"><NTag :type="health.ffmpeg.available?'success':'error'">{{ health.ffmpeg.message }}</NTag><div>{{ health.ffmpeg.ffmpegPath||'未配置 FFMPEG_PATH' }}</div></NDescriptionsItem></NDescriptions></CollapseCard>
    </NSpace>
    <NModal v-model:show="channelOpen" preset="card" :title="editing?'编辑频道':'新增频道'" class="w-520px max-w-95vw"><NForm><NFormItem label="名称"><NInput v-model:value="channelForm.name" maxlength="20" /></NFormItem><NFormItem label="排序"><NInputNumber v-model:value="channelForm.sortOrder" /></NFormItem><NFormItem label="启用"><NSwitch v-model:value="channelForm.enabled" /></NFormItem><NFormItem label="显示全部帖子"><div><NSwitch v-model:value="channelForm.showAllPosts" /><div class="mt-8px text-12px opacity-60">开启后显示所有频道已发布的帖子；关闭后仅显示发布到本频道的帖子</div></div></NFormItem><NFormItem label="允许发布"><div><NSwitch v-model:value="channelForm.allowPublish" :disabled="channelForm.isDefault" /><div class="mt-8px text-12px opacity-60">关闭后所有用户均不能向该频道发布，已有帖子仍可浏览；默认发布频道需先更换默认设置</div></div></NFormItem><NFormItem label="只能管理员发布"><div><NSwitch v-model:value="channelForm.adminOnly" :disabled="!channelForm.allowPublish" /><div class="mt-8px text-12px opacity-60">开启后仅官方账号可发布，普通用户选择版区时不显示</div></div></NFormItem><NFormItem label="默认发布频道"><NSwitch v-model:value="channelForm.isDefault" :disabled="!channelForm.allowPublish" /><span class="ml-8px">设置后会替换原默认频道</span></NFormItem></NForm><template #footer><NSpace justify="end"><NButton @click="channelOpen=false">取消</NButton><NButton type="primary" @click="saveChannel">保存</NButton></NSpace></template></NModal>
    <NModal v-model:show="entryOpen" preset="card" :title="editing?'编辑入口':'新增入口'" class="w-660px max-w-95vw"><NForm label-placement="left" label-width="110"><NFormItem label="名称"><NInput v-model:value="entryForm.name" maxlength="20" /></NFormItem><NFormItem label="图标"><ImageUpload v-model="entryForm.iconUrl!" /></NFormItem><EntryTargetEditor v-model:target-type="entryForm.targetType" v-model:link-url="entryForm.linkUrl" v-model:open-mode="entryForm.openMode" v-model:show-topbar="entryForm.showTopbar" /><NFormItem label="排序"><NInputNumber v-model:value="entryForm.sortOrder" /></NFormItem><NFormItem label="启用"><NSwitch v-model:value="entryForm.enabled" /></NFormItem></NForm><template #footer><NSpace justify="end"><NButton @click="entryOpen=false">取消</NButton><NButton type="primary" @click="saveEntry">保存</NButton></NSpace></template></NModal>
  </div>
</template>
