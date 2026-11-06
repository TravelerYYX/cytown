<script setup lang="ts">
import { computed, ref } from 'vue';
import { request } from '@/service/request';
import { mediaUrl } from '@/utils/service';
defineOptions({name:'ImageUpload'});
const props=withDefaults(defineProps<{modelValue:string;previewSize?:number;fixedWebp?:boolean;originalBytes?:number;processedBytes?:number}>(),{previewSize:64,fixedWebp:false,originalBytes:0,processedBytes:0});
const emit=defineEmits<{(e:'update:modelValue',value:string):void;(e:'uploaded',value:{url:string;originalBytes:number;processedBytes:number}):void}>();
const lastSizes=ref<{originalBytes:number;processedBytes:number}>();
const sizes=computed(()=>lastSizes.value||{originalBytes:props.originalBytes,processedBytes:props.processedBytes});
function bytes(n:number){return n>=1048576?(n/1048576).toFixed(2)+' MB':(n/1024).toFixed(2)+' KB';}
const format=ref<'webp'|'jpg'|'png'>('webp'); const quality=ref(75); const loading=ref(false); const input=ref<HTMLInputElement>(); const selectedFile=ref<File>();
const preview=computed(()=>props.modelValue?mediaUrl(props.modelValue):'');
function choose(){input.value?.click();}
async function uploadFile(file:File){
 if(file.size>10*1024*1024){window.$message?.error('图片大小不能超过 10MB');return;}
 if(!['image/jpeg','image/png','image/webp'].includes(file.type)){window.$message?.error('仅支持 jpg、png、webp 图片');return;}
 const data=new FormData();data.append('file',file);data.append('format',props.fixedWebp?'webp':format.value);data.append('quality',String(quality.value));loading.value=true;
 try{const result=await request<{url:string;originalBytes:number;processedBytes:number}>({url:'/image/upload',method:'post',data,headers:{'Content-Type':'multipart/form-data'}});if(!result.error&&result.data){lastSizes.value=result.data;emit('update:modelValue',result.data.url);emit('uploaded',result.data);window.$message?.success('图片上传成功');}}
 finally{loading.value=false;}
}
async function selected(event:Event){const file=(event.target as HTMLInputElement).files?.[0];if(!file)return;selectedFile.value=file;await uploadFile(file);(event.target as HTMLInputElement).value='';}
</script>
<template>
 <div class="flex flex-wrap items-center gap-12px">
  <NImage v-if="preview" :src="preview" object-fit="cover" :width="previewSize" :height="previewSize" class="rounded-8px" />
  <div v-else class="upload-placeholder" :style="{width:previewSize+'px',height:previewSize+'px'}">暂无图片</div>
  <input ref="input" class="hidden" type="file" accept="image/jpeg,image/png,image/webp" @change="selected">
  <NButton :loading="loading" @click="choose">选择图片</NButton>
  <NButton v-if="selectedFile" :loading="loading" @click="uploadFile(selectedFile)">按当前参数重传</NButton>
  <NTag v-if="fixedWebp">自动转 WebP</NTag><NSelect v-else v-model:value="format" :options="[{label:'WebP',value:'webp'},{label:'JPG',value:'jpg'},{label:'PNG',value:'png'}]" class="w-100px" />
  <div class="w-180px"><NSlider v-model:value="quality" :min="1" :max="100" :disabled="format==='png'" /><div class="text-12px text-gray">{{format==='png'?'PNG 为无损格式':'压缩率 '+quality+'%'}}</div></div>
  <div v-if="sizes.originalBytes" class="text-12px text-gray">处理前 {{bytes(sizes.originalBytes)}} → 处理后 {{bytes(sizes.processedBytes)}}（{{((1-sizes.processedBytes/sizes.originalBytes)*100).toFixed(1)}}%）</div>
 </div>
</template>
<style scoped>.upload-placeholder{display:flex;align-items:center;justify-content:center;border:1px dashed #ccc;border-radius:8px;color:#999;font-size:12px}.hidden{display:none}</style>
