<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { request } from '@/service/request';
import { useAuthStore } from '@/store/modules/auth';
const auth=useAuthStore(),baseUrl=ref(''),loading=ref(true),saving=ref(false),error=ref('');
async function load(){loading.value=true;try{const result=await request<{baseUrl:string}>({url:'/post-share-settings'});if(result.data){baseUrl.value=result.data.baseUrl;error.value='';}else error.value='分享配置读取失败，请重试';}finally{loading.value=false;}}
async function save(){saving.value=true;try{const result=await request<{baseUrl:string}>({url:'/post-share-settings',method:'post',data:{baseUrl:baseUrl.value}});if(result.data){baseUrl.value=result.data.baseUrl;window.$message?.success('分享配置已保存');}}finally{saving.value=false;}}
onMounted(load);
</script>
<template>
 <NCard title="帖子分享配置" :bordered="false">
  <NSpin :show="loading"><NAlert v-if="error" type="error" class="mb-16px">{{error}} <NButton text @click="load">重新加载</NButton></NAlert>
   <NForm :disabled="auth.isReadOnly||loading" label-placement="left" label-width="190"><NFormItem label="分享链接域名"><NInput v-model:value="baseUrl" placeholder="留空使用当前后端地址，如 https://share.example.com" maxlength="500" /></NFormItem>
    <NAlert type="info" class="mb-16px">留空时使用默认地址。配置独立域名后，将该域名的 /app/share/ 和媒体资源路径转发至当前后端，已有默认链接仍可访问。</NAlert>
    <NButton type="primary" :disabled="auth.isReadOnly||loading||!!error" :loading="saving" @click="save">保存分享配置</NButton>
   </NForm></NSpin>
 </NCard>
</template>
