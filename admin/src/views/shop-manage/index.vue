<script setup lang="ts">
import { onMounted, ref } from 'vue';
import ImageUpload from '@/components/image-upload/index.vue';
import {
  loadShopOrders, loadShopProducts, loadShopSettings, refreshShopOrder, saveShopProduct, saveShopSettings
} from '@/service/api/shop';
import type { ShopOrder, ShopProduct, ShopTier } from '@/service/api/shop';
import { mediaUrl } from '@/utils/service';

const products = ref<ShopProduct[]>([]);
const tiers = ref<ShopTier[]>([]);
const loading = ref(false);
const editing = ref(false);
const saving = ref(false);
const draft = ref<ShopProduct>();
const priceYuan = ref<number | null>(null);
const h5BaseUrl = ref('');
const settingsSaving = ref(false);
const orders = ref<ShopOrder[]>([]);
const search = ref('');
const orderPage = ref(1);
const total = ref(0);
const orderLoading = ref(false);
const checking = ref('');
const statusNames: Record<string, string> = {
  PENDING: '待支付／待确认', PAID: '已付款待发放', FULFILLED: '已发放', CLOSED: '已关闭'
};

async function loadProducts() {
  loading.value = true;
  try {
    const result = await loadShopProducts();
    if (result.data) {
      products.value = result.data.products;
      tiers.value = result.data.tiers;
    }
  } finally { loading.value = false; }
}

function edit(row?: ShopProduct) {
  const lastSort = Math.max(0, ...products.value.map(product => product.sortOrder));
  draft.value = row ? { ...row, images: [...row.images], discounts: { ...row.discounts } } : {
    id: '', version: 0, name: '', description: '', images: [''], frameUrl: '', priceFen: 300,
    discounts: {}, sortOrder: lastSort + 1, enabled: false, soldCount: 0
  };
  priceYuan.value = draft.value.priceFen / 100;
  editing.value = true;
}

async function save() {
  const row = draft.value;
  if (!row || saving.value) return;
  const images = row.images.filter(Boolean);
  if (!row.name.trim() || !images.length || !row.frameUrl || priceYuan.value == null || priceYuan.value < 0.1) {
    window.$message?.warning('请填写商品名称、图片、头像框和标价');
    return;
  }
  saving.value = true;
  try {
    const result = await saveShopProduct({ ...row, images, priceFen: Math.round(priceYuan.value * 100) });
    if (!result.error) {
      window.$message?.success('商品配置已保存');
      editing.value = false;
      await loadProducts();
    }
  } finally { saving.value = false; }
}

async function saveAddress() {
  settingsSaving.value = true;
  try {
    const result = await saveShopSettings(h5BaseUrl.value);
    if (result.data) {
      h5BaseUrl.value = result.data.h5BaseUrl;
      window.$message?.success('H5 地址已保存');
    }
  } finally { settingsSaving.value = false; }
}

let orderRevision = 0;
async function listOrders(page = 1) {
  const revision = ++orderRevision;
  orderPage.value = page;
  orderLoading.value = true;
  try {
    const result = await loadShopOrders(page, search.value.trim());
    if (revision === orderRevision && result.data) {
      orders.value = result.data.records;
      total.value = result.data.total;
    }
  } finally { if (revision === orderRevision) orderLoading.value = false; }
}

async function check(order: ShopOrder) {
  if (checking.value) return;
  checking.value = order.id;
  try {
    const result = await refreshShopOrder(order.id);
    if (!result.error) await listOrders(orderPage.value);
  } finally { checking.value = ''; }
}

onMounted(async () => {
  await Promise.all([
    loadProducts(), loadShopSettings().then(result => { if (result.data) h5BaseUrl.value = result.data.h5BaseUrl; })
  ]);
});
</script>

<template>
  <NCard title="商铺管理">
    <NTabs type="line" @update:value="value => value === 'orders' && listOrders()">
      <NTabPane name="products" tab="商品">
        <NSpace class="mb-16px">
          <NButton type="primary" @click="edit()">新建商品</NButton>
          <NButton :loading="loading" @click="loadProducts">刷新</NButton>
        </NSpace>
        <NSpin :show="loading">
          <div class="shop-table">
            <NTable>
              <thead><tr><th>商品</th><th>标价</th><th>排序</th><th>已售</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="item in products" :key="item.id">
                  <td><NSpace align="center"><NImage :src="mediaUrl(item.images[0])" :width="48" :height="48" object-fit="cover" />{{ item.name }}</NSpace></td>
                  <td>¥{{ (item.priceFen / 100).toFixed(2) }}／{{ (item.priceFen / 10).toFixed(1) }} 币</td>
                  <td>{{ item.sortOrder }}</td><td>{{ item.soldCount }}</td>
                  <td><NTag :type="item.enabled ? 'success' : 'default'">{{ item.enabled ? '已上架' : '已下架' }}</NTag></td>
                  <td><NButton size="small" @click="edit(item)">编辑</NButton></td>
                </tr>
                <tr v-if="!products.length"><td colspan="6">暂无商品</td></tr>
              </tbody>
            </NTable>
          </div>
        </NSpin>
      </NTabPane>
      <NTabPane name="orders" tab="订单">
        <NSpace class="mb-16px">
          <NInput v-model:value="search" placeholder="订单号、交易号或用户 ID" clearable @keyup.enter="listOrders()" />
          <NButton :loading="orderLoading" @click="listOrders()">查询</NButton>
        </NSpace>
        <NSpin :show="orderLoading">
          <div class="shop-table">
            <NTable>
              <thead><tr><th>订单／用户</th><th>商品</th><th>实付</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="order in orders" :key="order.id">
                  <td>{{ order.id }}<br />用户 {{ order.userId }}</td><td>{{ order.productName }}</td>
                  <td>{{ order.method === 'COIN' ? order.coins.toFixed(1) + ' 币' : '¥' + (order.amountFen / 100).toFixed(2) }}</td>
                  <td>{{ statusNames[order.status] || order.status }}<br /><NText v-if="order.errorMessage" depth="3">{{ order.errorMessage }}</NText></td>
                  <td>{{ order.createdAt.replace('T', ' ') }}</td>
                  <td><NButton v-if="order.status === 'PENDING' || order.status === 'PAID'" size="small" :loading="checking === order.id" @click="check(order)">补查／重试</NButton></td>
                </tr>
                <tr v-if="!orders.length"><td colspan="6">暂无订单</td></tr>
              </tbody>
            </NTable>
          </div>
          <NPagination class="mt-16px" :page="orderPage" :page-size="20" :item-count="total" @update:page="listOrders" />
        </NSpin>
      </NTabPane>
      <NTabPane name="settings" tab="分享与支付">
        <NAlert type="info" class="mb-16px">商品分享和支付宝 H5 返回共用该地址。支付宝商户参数在现有「支付宝配置」中管理。</NAlert>
        <NFormItem label="商铺 H5 基础地址"><NInput v-model:value="h5BaseUrl" placeholder="https://你的站点域名" /></NFormItem>
        <NButton type="primary" :loading="settingsSaving" @click="saveAddress">保存地址</NButton>
      </NTabPane>
    </NTabs>
    <NModal v-model:show="editing" preset="card" :title="draft?.id ? '编辑商品' : '新建商品'" class="shop-editor" :mask-closable="!saving">
      <NForm v-if="draft">
        <NFormItem label="名称"><NInput v-model:value="draft.name" maxlength="80" /></NFormItem>
        <NFormItem label="商品说明"><NInput v-model:value="draft.description" maxlength="500" type="textarea" /></NFormItem>
        <NFormItem label="商品图（首张为封面）">
          <NSpace vertical>
            <NSpace v-for="(_, i) in draft.images" :key="i" align="center">
              <ImageUpload v-model="draft.images[i]" fixed-webp />
              <NButton v-if="draft.images.length > 1" size="small" @click="draft.images.splice(i, 1)">移除</NButton>
            </NSpace>
            <NButton v-if="draft.images.length < 10" size="small" @click="draft.images.push('')">添加商品图</NButton>
          </NSpace>
        </NFormItem>
        <NFormItem label="佩戴素材（透明 PNG 或 WebP）"><ImageUpload v-model="draft.frameUrl" /></NFormItem>
        <NFormItem label="人民币标价（元）"><NInputNumber v-model:value="priceYuan" :min="0.1" :max="1000000" :precision="2" /></NFormItem>
        <NFormItem label="会员实付比例（%）">
          <NSpace>
            <div v-for="tier in tiers" :key="tier.id" class="shop-discount">
              <NText depth="3">{{ tier.name }}</NText>
              <NInputNumber :value="draft.discounts[tier.id] ?? 100" :min="1" :max="100" :precision="0"
                @update:value="value => { if (draft) draft.discounts[tier.id] = value ?? 100; }" />
            </div>
          </NSpace>
        </NFormItem>
        <NFormItem label="排序（从小到大）"><NInputNumber v-model:value="draft.sortOrder" :precision="0" /></NFormItem>
        <NFormItem label="上架"><NSwitch v-model:value="draft.enabled" /></NFormItem>
        <NButton type="primary" :loading="saving" @click="save">保存商品</NButton>
      </NForm>
    </NModal>
  </NCard>
</template>

<style scoped>
.shop-table { overflow-x: auto; }
.shop-editor { width: min(820px, 94vw); }
.shop-discount { width: 110px; }
</style>
