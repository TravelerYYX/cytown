<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { NButton, NPopconfirm, NSpace } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { deleteReportOption, fetchReportDetail, fetchReportOptions, fetchReports, saveReportOption } from '@/service/api/report';
import type { ReportDetail, ReportOption, UserReport } from '@/service/api/report';
import { mediaUrl } from '@/utils/service';
defineOptions({name:'ReportManage'});
const tab=ref('records'),loading=ref(false),saving=ref(false),optionOpen=ref(false),detailOpen=ref(false),detailLoading=ref(false);
const records=ref<UserReport[]>([]),options=ref<{types:ReportOption[];reasons:ReportOption[]}>({types:[],reasons:[]});
const detail=ref<ReportDetail|null>(null),page=ref(1),pageSize=ref(20),total=ref(0);
const form=reactive<{id?:string;kind:'type'|'reason';name:string;sortOrder:number}>({kind:'type',name:'',sortOrder:0});
const optionRows=computed(()=>tab.value==='types'?options.value.types:options.value.reasons);
let listRevision=0,detailRevision=0;
async function load(){const rev=++listRevision;loading.value=true;try{const [list,opts]=await Promise.all([fetchReports(page.value,pageSize.value),fetchReportOptions()]);if(rev!==listRevision)return;if(list.data){records.value=list.data.records;total.value=list.data.total;}if(opts.data)options.value=opts.data;}finally{if(rev===listRevision)loading.value=false;}}
async function showDetail(row:UserReport){const rev=++detailRevision;detail.value=null;detailOpen.value=true;detailLoading.value=true;try{const r=await fetchReportDetail(row.id);if(rev===detailRevision&&r.data)detail.value=r.data;}finally{if(rev===detailRevision)detailLoading.value=false;}}
function edit(row?:ReportOption){Object.assign(form,{id:row?.id,kind:row?.kind||(tab.value==='types'?'type':'reason'),name:row?.name||'',sortOrder:row?.sortOrder??optionRows.value.length});optionOpen.value=true;}
async function save(){if(!form.name.trim()){window.$message?.warning('请输入选项名称');return;}saving.value=true;try{const r=await saveReportOption({...form,name:form.name.trim()},!!form.id);if(!r.error){optionOpen.value=false;window.$message?.success('已保存');await load();}}finally{saving.value=false;}}
async function remove(row:ReportOption){const r=await deleteReportOption(row.id);if(!r.error){window.$message?.success('已删除，历史举报保留');await load();}}
const columns:DataTableColumns<UserReport>=[
  {title:'举报者',key:'reporterName',minWidth:160,render:r=>`${r.reporterName}（ID：${r.reporterId}）`},
  {title:'被举报者',key:'reportedUserName',minWidth:160,render:r=>`${r.reportedUserName}（ID：${r.reportedUserId}）`},
  {title:'举报对象',key:'targetKind',minWidth:140,render:r=>`${r.targetKind==='post'?'帖子':'用户'} ${r.targetId}`},
  {title:'类型',key:'typeName',minWidth:120},{title:'理由',key:'reasonName',minWidth:180},
  {title:'提交时间',key:'createTime',minWidth:170},
  {title:'操作',key:'op',width:100,render:r=>h(NButton,{size:'small',onClick:()=>showDetail(r)},()=> '查看详情')}
];
const optionColumns:DataTableColumns<ReportOption>=[{title:'名称',key:'name'},{title:'排序',key:'sortOrder',width:100},
  {title:'操作',key:'op',width:200,render:r=>h(NSpace,{},()=>[h(NButton,{size:'small',onClick:()=>edit(r)},()=> '编辑'),h(NPopconfirm,{onPositiveClick:()=>remove(r)},{trigger:()=>h(NButton,{size:'small',type:'error'},()=> '删除'),default:()=> '删除后不再提供选择，历史举报不受影响。确定删除？'})])}];
const rowProps=(row:UserReport)=>({style:'cursor:pointer',onClick:(e:MouseEvent)=>{if(!(e.target as HTMLElement).closest('button'))void showDetail(row);}});
function changePage(value:number){page.value=value;void load();}
onMounted(load);
</script>
<template>
  <div>
  <NCard title="举报管理" :bordered="false">
    <NTabs v-model:value="tab" type="line" animated>
      <NTabPane name="records" tab="举报记录">
        <NDataTable :columns="columns" :data="records" :loading="loading" :row-key="(row:UserReport)=>row.id" :row-props="rowProps" :scroll-x="1100" />
        <div class="mt-16px flex justify-end"><NPagination :page="page" :page-size="pageSize" :item-count="total" show-size-picker :page-sizes="[10,20,50]" @update:page="changePage" @update:page-size="(value:number)=>{pageSize=value;changePage(1);}" /></div>
      </NTabPane>
      <NTabPane name="types" tab="举报类型"><NButton class="mb-16px" type="primary" @click="edit()">新增类型</NButton><NDataTable :columns="optionColumns" :data="optionRows" :loading="loading" /></NTabPane>
      <NTabPane name="reasons" tab="举报理由"><NButton class="mb-16px" type="primary" @click="edit()">新增理由</NButton><NDataTable :columns="optionColumns" :data="optionRows" :loading="loading" /></NTabPane>
    </NTabs>
  </NCard>
  <NModal v-model:show="optionOpen" preset="card" :title="form.id?'编辑举报选项':'新增举报选项'" class="w-520px max-w-95vw">
    <NForm label-placement="left" label-width="80"><NFormItem label="名称" required><NInput v-model:value="form.name" :maxlength="100" show-count /></NFormItem><NFormItem label="排序"><NInputNumber v-model:value="form.sortOrder" :precision="0" /></NFormItem></NForm>
    <template #footer><NSpace justify="end"><NButton :disabled="saving" @click="optionOpen=false">取消</NButton><NButton type="primary" :loading="saving" @click="save">保存</NButton></NSpace></template>
  </NModal>
  <NModal v-model:show="detailOpen" preset="card" title="举报详情" class="w-800px max-w-95vw">
    <NSpin :show="detailLoading"><template v-if="detail">
      <NDescriptions bordered :column="2" label-placement="left">
        <NDescriptionsItem label="举报编号">{{detail.report.id}}</NDescriptionsItem><NDescriptionsItem label="提交时间">{{detail.report.createTime}}</NDescriptionsItem>
        <NDescriptionsItem label="举报者">{{detail.report.reporterName}}（ID：{{detail.report.reporterId}}）</NDescriptionsItem><NDescriptionsItem label="被举报者">{{detail.report.reportedUserName}}（ID：{{detail.report.reportedUserId}}）</NDescriptionsItem>
        <NDescriptionsItem label="举报类型">{{detail.report.typeName}}</NDescriptionsItem><NDescriptionsItem label="举报理由">{{detail.report.reasonName}}</NDescriptionsItem>
        <NDescriptionsItem label="举报内容" :span="2"><div class="report-detail-copy">{{detail.report.content}}</div></NDescriptionsItem>
      </NDescriptions>
      <NDivider>证据图片</NDivider><NImageGroup><NSpace v-if="detail.images.length"><NImage v-for="url in detail.images" :key="url" :src="mediaUrl(url)" width="120" height="120" object-fit="cover" /></NSpace><NEmpty v-else description="未提供图片" /></NImageGroup>
      <NDivider>提交时的{{detail.report.targetKind==='post'?'帖子':'用户'}}信息</NDivider>
      <NDescriptions bordered :column="1" label-placement="left"><NDescriptionsItem v-if="detail.targetSnapshot.avatarUrl" label="头像"><NImage :src="mediaUrl(detail.targetSnapshot.avatarUrl)" width="80" height="80" object-fit="cover" /></NDescriptionsItem><NDescriptionsItem label="昵称">{{detail.targetSnapshot.nickname}}</NDescriptionsItem><NDescriptionsItem label="签名">{{detail.targetSnapshot.signature||'未设置'}}</NDescriptionsItem><template v-if="detail.report.targetKind==='post'"><NDescriptionsItem label="帖子ID">{{detail.targetSnapshot.postId}}</NDescriptionsItem><NDescriptionsItem label="标题">{{detail.targetSnapshot.title}}</NDescriptionsItem><NDescriptionsItem label="正文"><div class="report-detail-copy">{{detail.targetSnapshot.content||'无文字正文'}}</div></NDescriptionsItem></template></NDescriptions>
    </template><NEmpty v-else-if="!detailLoading" description="详情加载失败，请关闭后重试" /></NSpin>
  </NModal>
  </div>
</template>
<style scoped>.report-detail-copy{white-space:pre-wrap;overflow-wrap:anywhere;max-height:360px;overflow:auto;margin:0;}</style>
