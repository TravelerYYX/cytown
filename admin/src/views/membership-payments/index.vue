<script setup lang="ts">
import {onMounted,ref} from 'vue';
import {loadPaymentProfiles,savePaymentProfile} from '@/service/api/membership';
import type {PaymentProfile} from '@/service/api/membership';
const profiles=ref<PaymentProfile[]>([]),busy=ref(false),error=ref('');
const environments=[{label:'沙箱测试',value:'SANDBOX'},{label:'正式收款',value:'PRODUCTION'}];
const modes=[{label:'普通公钥',value:'PUBLIC_KEY'},{label:'证书',value:'CERT'}];
async function load(){busy.value=true;try{const r=await loadPaymentProfiles();if(r.data)profiles.value=r.data;else error.value='支付配置加载失败';}finally{busy.value=false;}}
async function save(p:PaymentProfile){busy.value=true;error.value='';try{const r=await savePaymentProfile(p);if(r.data){Object.assign(p,r.data);window.$message?.success('配置格式校验通过并保存；请继续支付联调');}else error.value='保存失败，请根据提示检查配置';}finally{p.privateKey='';busy.value=false;}}
function copyApp(){const a=profiles.value.find(p=>p.channel==='APP'),b=profiles.value.find(p=>p.channel==='H5');if(a&&b)Object.assign(b,{...a,id:b.id,channel:'H5',returnUrl:b.returnUrl,privateKey:''});window.$message?.info('已复制公开配置，请为 H5 填写同一应用私钥后保存');}
async function cert(event:Event,p:PaymentProfile,key:'appCert'|'alipayCert'|'rootCert'){const input=event.target as HTMLInputElement;const f=input.files?.[0];if(!f)return;if(f.size>100000){error.value='证书文件不能超过100KB';return;}p[key]=await f.text();input.value='';}
onMounted(load);
</script>
<template><NSpace vertical size="large">
 <NAlert type="info">商户需开通 App 支付和手机网站支付。应用私钥仅在填写时提交，留空保留原私钥；历史订单继续使用原配置。证书文件仅存入支付配置，不进入公开素材库。</NAlert>
 <NAlert v-if="error" type="error">{{error}}</NAlert><NSpace><NButton :loading="busy" @click="load">重新加载</NButton><NButton @click="copyApp">将 App 公开配置复制到 H5</NButton></NSpace>
 <NCard v-for="p in profiles" :key="p.channel" :title="p.channel==='APP'?'Android App 支付':'H5 手机网站支付'">
 <NForm label-placement="top"><NGrid :cols="2" :x-gap="24">
 <NFormItemGi label="环境"><NSelect v-model:value="p.environment" :options="environments" /></NFormItemGi><NFormItemGi label="签名配置模式"><NSelect v-model:value="p.mode" :options="modes" /></NFormItemGi>
 <NFormItemGi label="APPID"><NInput v-model:value="p.appId" /></NFormItemGi><NFormItemGi label="收款方 PID"><NInput v-model:value="p.sellerId" /></NFormItemGi></NGrid>
 <NFormItem :label="'应用私钥'+(p.id?'（已保存，留空保持）':'（必填）')"><NInput v-model:value="p.privateKey" type="password" show-password-on="click" autocomplete="off" /></NFormItem>
 <NFormItem v-if="p.mode==='PUBLIC_KEY'" label="支付宝公钥"><NInput v-model:value="p.publicKey" type="textarea" /></NFormItem>
 <template v-else><NFormItem label="应用公钥证书"><input type="file" accept=".crt,.cer,.pem" @change="e=>cert(e,p,'appCert')" /><NInput v-model:value="p.appCert" type="textarea" /></NFormItem>
 <NFormItem label="支付宝公钥证书"><input type="file" accept=".crt,.cer,.pem" @change="e=>cert(e,p,'alipayCert')" /><NInput v-model:value="p.alipayCert" type="textarea" /></NFormItem>
 <NFormItem label="支付宝根证书"><input type="file" accept=".crt,.cer,.pem" @change="e=>cert(e,p,'rootCert')" /><NInput v-model:value="p.rootCert" type="textarea" /></NFormItem></template>
 <NFormItem label="支付结果异步通知地址"><NInput v-model:value="p.notifyUrl" placeholder="https://你的后端域名/app/membership/alipay/notify" /></NFormItem>
 <NFormItem v-if="p.channel==='H5'" label="支付后返回地址"><NInput v-model:value="p.returnUrl" placeholder="https://你的H5域名/#/pages/membership/subscription" /></NFormItem>
 <NButton type="primary" :loading="busy" @click="save(p)">校验并保存此渠道</NButton></NForm></NCard>
 </NSpace></template>
