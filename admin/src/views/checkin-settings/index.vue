<script setup lang="ts">
import {computed,onMounted,ref} from 'vue';
import {onBeforeRouteLeave} from 'vue-router';
import ImageUpload from '@/components/image-upload/index.vue';
import {mediaUrl} from '@/utils/service';
import {fetchCheckinConfig,saveCheckinConfig,fetchCoinPunchConfig,saveCoinPunchConfig} from '@/service/api/checkin';
import type {CheckinConfig,CheckinDay} from '@/service/api/checkin';
const config=ref<CheckinConfig>();const loading=ref(false),saving=ref(false),error=ref(''),baseline=ref('');
const dirty=computed(()=>config.value!=null&&JSON.stringify(config.value)!==baseline.value);
const selected=ref(1);const day=computed(()=>config.value?.days[selected.value-1]);
const frameOptions=computed(()=>[{label:'不赠送头像框',value:''},...(config.value?.frames||[]).map(f=>({label:f.name,value:f.id}))]);
const primaryOptions=computed(()=>{const d=day.value;if(!d)return [];return [{label:'次元币',value:'coins',disabled:!d.coins},{label:'经验',value:'experience',disabled:!d.experience},{label:'头像框',value:'frame',disabled:!d.frameId}];});
function normalize(d:CheckinDay){if(d.primary==='coins'&&!d.coins||d.primary==='experience'&&!d.experience||d.primary==='frame'&&!d.frameId)d.primary=d.coins?'coins':d.experience?'experience':'frame';}
async function load(){if(dirty.value&&!window.confirm('有未保存修改，确定放弃并重新加载？'))return;loading.value=true;error.value='';try{const r=await fetchCheckinConfig();if(r.error||!r.data){error.value='配置加载失败，请重试';return;}config.value=r.data;baseline.value=JSON.stringify(r.data);}finally{loading.value=false;}}
async function save(){if(!config.value||saving.value)return;saving.value=true;try{const r=await saveCheckinConfig(config.value);if(!r.error&&r.data){config.value=r.data;baseline.value=JSON.stringify(r.data);window.$message?.success('签到配置已保存');}else error.value='保存失败，修改已保留；配置冲突时请重新加载';}finally{saving.value=false;}}
function addFrame(){if(!config.value)return;let n=1;while(config.value.frames.some(f=>f.id==='reward-'+n))n++;config.value.frames.push({id:'reward-'+n,name:'新奖励头像框',image:{url:'',originalBytes:0,processedBytes:0}});}
const punch = ref<{ points: number | null; coins: number | null }>();
const punchBaseline = ref('');
const punchLoading = ref(false);
const punchSaving = ref(false);
const punchError = ref('');
const punchDirty = computed(() => punch.value != null && JSON.stringify(punch.value) !== punchBaseline.value);

async function loadPunch() {
  if (punchDirty.value && !window.confirm('确定放弃未保存的打卡奖励修改？')) return;
  punchLoading.value = true;
  punchError.value = '';
  try {
    const result = await fetchCoinPunchConfig();
    if (result.error || !result.data) {
      punchError.value = '打卡奖励加载失败，请重试';
      return;
    }
    punch.value = result.data;
    punchBaseline.value = JSON.stringify(result.data);
  } catch {
    punchError.value = '打卡奖励加载失败，请重试';
  } finally {
    punchLoading.value = false;
  }
}

async function savePunch() {
  const points = punch.value?.points, coins = punch.value?.coins;
  if (points == null || coins == null || !Number.isInteger(points) || !Number.isInteger(coins * 10)
      || points < 0 || points > 1000000 || coins < 0 || coins > 1000000) {
    punchError.value = '积分须为整数，次元币最多一位小数，范围均为 0 至 1000000';
    return;
  }
  punchSaving.value = true;
  punchError.value = '';
  try {
    const result = await saveCoinPunchConfig({ points, coins });
    if (result.error || !result.data) {
      punchError.value = '保存失败，修改已保留';
      return;
    }
    punch.value = result.data;
    punchBaseline.value = JSON.stringify(result.data);
    window.$message?.success('打卡奖励已保存');
  } catch {
    punchError.value = '保存失败，修改已保留';
  } finally {
    punchSaving.value = false;
  }
}

onBeforeRouteLeave(() => (!dirty.value && !punchDirty.value) || window.confirm('奖励配置尚未保存，确定离开？'));
onMounted(() => { void load(); void loadPunch(); });
</script>
<template>
 <div class="flex flex-col gap-16px">
  <NCard title="每日签到配置"><template #header-extra><NSpace><NTag v-if="dirty" type="warning">未保存</NTag><NButton :loading="loading" @click="load">重新加载</NButton><NButton type="primary" :loading="saving" :disabled="!config||loading" @click="save">保存配置</NButton></NSpace></template>
   <NAlert v-if="error" type="error" class="mb-16px">{{error}}</NAlert>
   <NAlert type="info">北京时间每日零点更新，每月循环使用31天模板，自动隐藏无效日期。补签只补当日奖励，不修复连续签到；修改不影响已领取奖励。所有图片复用现有压缩上传，自动转WebP。</NAlert>
   <NSpin :show="loading"><div v-if="config" class="mt-20px"><NFormItem label="每次补签价格（次元币）"><NInputNumber v-model:value="config.makeupPrice" :min="0" :max="1000000" :precision="1" /></NFormItem>
    <NFormItem label="次元币共用图标"><ImageUpload v-model="config.coinIcon.url" fixed-webp :original-bytes="config.coinIcon.originalBytes" :processed-bytes="config.coinIcon.processedBytes" @uploaded="a=>config&&(config.coinIcon=a)" /></NFormItem>
    <NFormItem label="经验共用图标"><ImageUpload v-model="config.experienceIcon.url" fixed-webp :original-bytes="config.experienceIcon.originalBytes" :processed-bytes="config.experienceIcon.processedBytes" @uploaded="a=>config&&(config.experienceIcon=a)" /></NFormItem>
   </div></NSpin>
  </NCard>
  <NCard title="次元币打卡奖励">
   <template #header-extra>
    <NSpace>
     <NTag v-if="punchDirty" type="warning">未保存</NTag>
     <NButton :loading="punchLoading" :disabled="punchSaving" @click="loadPunch">重新加载</NButton>
     <NButton type="primary" :loading="punchSaving" :disabled="!punch || punchLoading || !punchDirty" @click="savePunch">保存打卡奖励</NButton>
    </NSpace>
   </template>
   <NAlert v-if="punchError" type="error" class="mb-16px">{{punchError}}</NAlert>
   <NAlert type="info">次元币页每天可独立打卡一次。新配置在下一次领取时生效。</NAlert>
   <div v-if="punch" class="mt-20px">
    <NFormItem label="赠送积分"><NInputNumber v-model:value="punch.points" :disabled="punchSaving || punchLoading" :min="0" :max="1000000" :precision="0" /></NFormItem>
    <NFormItem label="赠送次元币"><NInputNumber v-model:value="punch.coins" :disabled="punchSaving || punchLoading" :min="0" :max="1000000" :precision="1" /></NFormItem>
   </div>
  </NCard>
  <NCard v-if="config" title="逐日奖励"><div class="day-grid"><NButton v-for="d in config.days" :key="d.day" :type="selected===d.day?'primary':'default'" class="day-button" @click="selected=d.day"><div>第{{d.day}}天</div><div class="text-12px">{{d.coins}}币 · {{d.experience}}经验{{d.frameId?' · 头像框':''}}</div></NButton></div>
   <div v-if="day" class="reward-editor"><h3>第{{day.day}}天奖励</h3><NSpace vertical>
    <NFormItem label="次元币"><NInputNumber v-model:value="day.coins" :min="0" :max="1000000" :precision="1" @update:value="()=>day&&normalize(day)" /></NFormItem>
    <NFormItem label="经验"><NInputNumber v-model:value="day.experience" :min="0" :max="1000000" :precision="0" @update:value="()=>day&&normalize(day)" /></NFormItem>
    <NFormItem label="赠送头像框"><NSelect v-model:value="day.frameId" :options="frameOptions" class="w-260px" @update:value="()=>day&&normalize(day)" /></NFormItem>
    <NFormItem v-if="day.frameId" label="头像框有效天数"><NInputNumber v-model:value="day.frameDays" :min="0" :max="36500" :precision="0" /><span class="ml-12px">0为永久；限时重复领取自动续期</span></NFormItem>
    <NFormItem label="卡片主图标"><NSelect v-model:value="day.primary" :options="primaryOptions" class="w-260px" /><span class="ml-12px">点击卡片查看组合奖励</span></NFormItem>
   </NSpace></div>
  </NCard>
  <NCard v-if="config" title="签到头像框素材"><template #header-extra><NButton @click="addFrame">新增头像框</NButton></template><div v-for="f in config.frames" :key="f.id" class="frame-editor"><NSpace align="center"><NTag>{{f.id}}</NTag><NInput v-model:value="f.name" placeholder="头像框名称" maxlength="40" class="w-240px" /><NImage v-if="f.image.url" :src="mediaUrl(f.image.url)" width="64" height="64" /></NSpace><ImageUpload v-model="f.image.url" fixed-webp :original-bytes="f.image.originalBytes" :processed-bytes="f.image.processedBytes" @uploaded="a=>f.image=a" /></div></NCard>
 </div>
</template>
<style scoped>.day-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(135px,1fr));gap:10px}.day-button{height:62px}.day-button :deep(.n-button__content){display:flex;flex-direction:column;line-height:20px;max-width:100%;overflow:hidden}.day-button :deep(.n-button__content>div){max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.reward-editor{margin-top:24px;padding:20px;border:1px solid #e6e9ef;border-radius:12px}.reward-editor h3{margin:0 0 20px}.frame-editor{padding:16px 0;border-bottom:1px solid #eee;display:flex;flex-direction:column;gap:14px}</style>
