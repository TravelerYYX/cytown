<script setup lang="ts">
import {h,onMounted,ref} from 'vue';
import {NButton} from 'naive-ui';
import type {DataTableColumns} from 'naive-ui';
import {loadMembershipOrders,refreshMembershipOrder} from '@/service/api/membership';
import type {MembershipOrder} from '@/service/api/membership';
const rows=ref<MembershipOrder[]>([]),busy=ref(false),error=ref(''),page=ref(1),total=ref(0),search=ref(''),status=ref<string|null>(null);
const labels:Record<string,string>={PENDING:'待付款/确认中',PAID:'已付款待发放',ACTIVE:'已开通',CLOSED:'已关闭'};
const options=Object.entries(labels).map(([value,label])=>({value,label}));
async function load(){busy.value=true;try{const r=await loadMembershipOrders({pageNum:page.value,pageSize:20,status:status.value||undefined,search:search.value||undefined});if(r.data){rows.value=r.data.records;total.value=r.data.total;error.value='';}else error.value='订单加载失败';}finally{busy.value=false;}}
async function refresh(row:MembershipOrder){busy.value=true;try{const r=await refreshMembershipOrder(row.id);if(r.data)await load();else error.value='补查失败，请稍后重试';}finally{busy.value=false;}}
const columns:DataTableColumns<MembershipOrder>=[{title:'订单号',key:'id',width:290},{title:'用户',key:'userId',width:100},{title:'套餐',key:'planId',render:r=>r.plan.name,width:100},{title:'实付金额',key:'amountFen',render:r=>'¥'+(r.amountFen/100).toFixed(2),width:100},{title:'渠道',key:'channel',width:80},{title:'状态',key:'status',render:r=>labels[r.status],width:150},{title:'支付宝交易号',key:'tradeNo',width:260},{title:'创建时间',key:'createdAt',width:180},{title:'会员延长至',key:'grantEnd',width:180},{title:'待处理原因',key:'errorMessage',width:280},{title:'操作',key:'action',width:100,fixed:'right',render:r=>h(NButton,{size:'small',disabled:busy.value||['ACTIVE','CLOSED'].includes(r.status),onClick:()=>refresh(r)},()=> '补查/重试')}];
onMounted(load);
</script>
<template><NSpace vertical size="large"><NAlert v-if="error" type="error">{{error}}</NAlert><NCard title="会员订单"><NSpace class="mb-16px"><NInput v-model:value="search" placeholder="订单号、用户ID或支付宝交易号" class="w-320px" /><NSelect v-model:value="status" :options="options" clearable placeholder="全部状态" class="w-200px" /><NButton :loading="busy" @click="page=1;load()">查询</NButton></NSpace><NDataTable :columns="columns" :data="rows" :loading="busy" :scroll-x="2000" :row-key="r=>r.id" /><NPagination v-model:page="page" :item-count="total" :page-size="20" class="mt-16px" @update:page="load" /></NCard></NSpace></template>
