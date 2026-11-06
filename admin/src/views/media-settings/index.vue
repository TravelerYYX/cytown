<script setup lang="ts">
import { useAuthStore } from '@/store/modules/auth';
import { computed, onMounted, reactive, ref } from 'vue';
import { getStorageConfig, saveStorageConfig, testStorageConfig, getMediaSettings, saveMediaSettings } from '@/service/api/media-config';
import type { StorageConfig, MediaSettings } from '@/service/api/media-config';
defineOptions({ name: 'MediaSettings' });
const props=withDefaults(defineProps<{section?:'all'|'storage'|'images'|'videos'}>(),{section:'all'});
const auth=useAuthStore();
const busy = ref(false);
const storage = reactive<StorageConfig>({ id:'', provider:'local', localDir:'', endpoint:'', region:'', bucket:'', accessKey:'', secretKey:'', domain:'', ftpHost:'', ftpPort:21, ftpUsername:'', ftpPassword:'', ftpRootDir:'/' });
const settings = reactive<MediaSettings>({ images:{coverMaxEdge:1280,coverQuality:78,previewMaxEdge:2560,previewQuality:85,pngCompression:6,cacheMaxBytes:3*1024**3}, videos:{segmentSeconds:4,maxFps:30,audioKbps:128,crf:23,concurrency:1,bitrates:{'1080':5000,'720':2800,'480':1400,'360':800}} });
const cacheGB = computed({ get:()=>settings.images.cacheMaxBytes/1024**3, set:(value:number)=>settings.images.cacheMaxBytes=Math.round(value*1024**3) });
const providers=[{label:'本地存储',value:'local'},{label:'阿里云 OSS',value:'oss'},{label:'七牛云',value:'qiniu'},{label:'远程 FTP',value:'ftp'}];
const regions=[{label:'华东 z0',value:'z0'},{label:'华北 z1',value:'z1'},{label:'华南 z2',value:'z2'},{label:'北美 na0',value:'na0'},{label:'东南亚 as0',value:'as0'}];
async function submitStorage(test=false){busy.value=true;try{const r=test?await testStorageConfig(storage):await saveStorageConfig(storage);if(!r.error){window.$message?.success(test?'连接检测通过':'存储配置已保存');if(!test){if(r.data)Object.assign(storage,r.data);storage.secretKey='';storage.ftpPassword='';}}}finally{busy.value=false;}}
async function submitProcessing(){busy.value=true;try{const latest=props.section==='all'?null:await getMediaSettings();if(latest?.error)return;const payload=latest?.data?{...latest.data,[props.section]:settings[props.section as 'images'|'videos']}:settings;const r=await saveMediaSettings(payload);if(!r.error)window.$message?.success('压缩配置已保存，新上传生效');}finally{busy.value=false;}}
onMounted(async()=>{const results=await Promise.all([getStorageConfig(),getMediaSettings()]);if(results[0].data)Object.assign(storage,results[0].data);if(results[1].data)Object.assign(settings,results[1].data);});
</script>
<template>
 <NSpace vertical :size="16">
  <NCard v-if="section==='all'||section==='storage'" title="存储配置" :bordered="false"><NForm :disabled="auth.isReadOnly" label-placement="left" label-width="150">
   <NFormItem label="当前存储"><NSelect v-model:value="storage.provider" :options="providers" @update:value="()=>{storage.accessKey='';storage.secretKey='';storage.region='';storage.ftpPassword='';}" /></NFormItem>
   <NFormItem v-if="storage.provider==='local'" label="本地绝对目录"><NInput v-model:value="storage.localDir" placeholder="例如 D:/ciyuantown/uploads" /></NFormItem>
   <template v-else-if="storage.provider==='ftp'">
    <NFormItem label="FTP 主机"><NInput v-model:value="storage.ftpHost" placeholder="ftp.example.com 或 IP，不含协议和端口" /></NFormItem>
    <NFormItem label="FTP 端口"><NInputNumber v-model:value="storage.ftpPort" :min="1" :max="65535" :precision="0" :show-button="false" :update-value-on-input="false" @blur="()=>{storage.ftpPort=storage.ftpPort??21;}" /></NFormItem>
    <NFormItem label="FTP 用户名"><NInput v-model:value="storage.ftpUsername" autocomplete="off" /></NFormItem>
    <NFormItem label="FTP 密码"><NInput v-model:value="storage.ftpPassword" type="password" show-password-on="click" autocomplete="new-password" placeholder="留空保留已保存 FTP 密码" /></NFormItem>
    <NFormItem label="FTP 根目录"><NInput v-model:value="storage.ftpRootDir" placeholder="例如 /media，默认为 /" /></NFormItem>
    <NAlert type="info" :show-icon="false" class="mb-16px">根目录按 FTP 登录后可见的路径填写，需已存在。访问域名应映射到此目录，例如 /media 对应 https://cdn.example.com/files。FTP 使用被动模式。</NAlert>
   </template>
   <template v-else>
    <NFormItem label="地域"><NSelect v-if="storage.provider==='qiniu'" v-model:value="storage.region" :options="regions" /><NInput v-else v-model:value="storage.region" placeholder="例如 cn-hangzhou" /></NFormItem>
    <NFormItem v-if="storage.provider==='oss'" label="Endpoint"><NInput v-model:value="storage.endpoint" placeholder="https://oss-cn-hangzhou.aliyuncs.com" /></NFormItem>
    <NFormItem label="Bucket"><NInput v-model:value="storage.bucket" /></NFormItem>
    <NFormItem label="Access Key"><NInput v-model:value="storage.accessKey" autocomplete="off" placeholder="已保存时显示脱敏值" /></NFormItem>
    <NFormItem label="Secret Key"><NInput v-model:value="storage.secretKey" type="password" show-password-on="click" autocomplete="new-password" placeholder="留空保留已保存密钥" /></NFormItem>
   </template>
   <NFormItem label="访问域名"><NInput v-model:value="storage.domain" placeholder="https://media.example.com，本地模式可留空" /></NFormItem>
   <NAlert type="info" :show-icon="false" class="mb-16px">切换存储只影响新上传。远程访问域名需允许 H5 与 App 请求清单、分片及图片；保存仍沿用作者下载设置。</NAlert>
   <NSpace><NButton :disabled="auth.isReadOnly" :loading="busy" @click="submitStorage(true)">连接检测</NButton><NButton :disabled="auth.isReadOnly" type="primary" :loading="busy" @click="submitStorage()">保存存储配置</NButton></NSpace>
  </NForm></NCard>
  <NCard v-if="section==='all'||section==='images'" title="图片压缩" :bordered="false"><NForm :disabled="auth.isReadOnly" label-placement="left" label-width="180">
   <NFormItem label="WebP 封面最长边"><NInputNumber v-model:value="settings.images.coverMaxEdge" :min="64" :max="8192" /><span class="ml-12px">px</span></NFormItem>
   <NFormItem label="WebP 封面质量"><NInputNumber v-model:value="settings.images.coverQuality" :min="1" :max="100" /></NFormItem>
   <NFormItem label="压缩预览最长边"><NInputNumber v-model:value="settings.images.previewMaxEdge" :min="64" :max="16384" /><span class="ml-12px">px</span></NFormItem>
   <NFormItem label="预览有损编码质量"><NInputNumber v-model:value="settings.images.previewQuality" :min="1" :max="100" /></NFormItem>
   <NFormItem label="PNG 压缩等级"><NInputNumber v-model:value="settings.images.pngCompression" :min="0" :max="9" /></NFormItem>
   <NFormItem label="客户端图片缓存上限"><NInputNumber v-model:value="cacheGB" :min="0.016" :max="32" :step="0.5" /><span class="ml-12px">GB（H5受浏览器额度限制）</span></NFormItem>
   <NButton :disabled="auth.isReadOnly" type="primary" :loading="busy" @click="submitProcessing">保存图片压缩配置</NButton>
  </NForm></NCard>
  <NCard v-if="section==='all'||section==='videos'" title="视频转码" :bordered="false"><NForm :disabled="auth.isReadOnly" label-placement="left" label-width="180">
   <NFormItem v-for="level in ['1080','720','480','360']" :key="level" :label="level+'P 最大视频码率'"><NInputNumber v-model:value="settings.videos.bitrates[level]" :min="100" :max="20000" /><span class="ml-12px">kbps</span></NFormItem>
   <NFormItem label="分片时长"><NInputNumber v-model:value="settings.videos.segmentSeconds" :min="2" :max="10" /><span class="ml-12px">秒</span></NFormItem>
   <NFormItem label="帧率上限"><NInputNumber v-model:value="settings.videos.maxFps" :min="1" :max="30" /></NFormItem>
   <NFormItem label="音频码率"><NInputNumber v-model:value="settings.videos.audioKbps" :min="32" :max="320" /><span class="ml-12px">kbps</span></NFormItem>
   <NFormItem label="CRF 编码质量"><NInputNumber v-model:value="settings.videos.crf" :min="0" :max="40" /><span class="ml-12px">数值越低，质量越高</span></NFormItem>
   <NFormItem label="转码并发任务"><NInputNumber v-model:value="settings.videos.concurrency" :min="1" :max="8" /></NFormItem>
   <NAlert type="info" :show-icon="false" class="mb-16px">最高1080P，不放大源视频。视频可先发布；默认自动模式，从720P或更低的可用档起播。</NAlert>
   <NButton :disabled="auth.isReadOnly" type="primary" :loading="busy" @click="submitProcessing">保存视频转码配置</NButton>
  </NForm></NCard>
 </NSpace>
</template>
