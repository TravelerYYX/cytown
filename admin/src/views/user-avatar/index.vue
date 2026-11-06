<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { NButton, NImage, NInputNumber, NPopconfirm, NSelect, NSpace, NSwitch, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  deleteSystemAvatar,
  fetchSystemAvatarAdminList,
  updateSystemAvatar,
  uploadSystemAvatar
} from '@/service/api';
import { mediaUrl } from '@/utils/service';

defineOptions({ name: 'UserAvatar' });

type Row = Api.P6.SystemAvatar;

const rows = ref<Row[]>([]);
const loading = ref(false);
const uploading = ref(false);
const open = ref(false);
const editing = ref(false);
/** -1 表示不过滤（全部），0 静态、1 动态，均保持 number 类型以匹配 NSelect。 */
const ALL = -1;
const filterType = ref<number>(ALL);
const fileInput = ref<HTMLInputElement | null>(null);
const form = reactive<Partial<Row>>({ id: undefined, type: 0, sort: 0, status: 0 });

const typeOptions = [
  { label: '静态头像', value: 0 },
  { label: '动态头像', value: 1 }
];
const filterOptions = [{ label: '全部', value: ALL }, ...typeOptions];

const filtered = computed(() => (filterType.value === ALL ? rows.value : rows.value.filter(item => item.type === filterType.value)));

async function load() {
  loading.value = true;
  try {
    const r = await fetchSystemAvatarAdminList();
    if (r.data) rows.value = r.data;
  } finally {
    loading.value = false;
  }
}

function pickFile() {
  fileInput.value?.click();
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (file.size > 10 * 1024 * 1024) {
    window.$message?.warning('头像大小不能超过 10MB');
    return;
  }
  uploading.value = true;
  try {
    const r = await uploadSystemAvatar(file, filterType.value === ALL ? 0 : filterType.value);
    if (!r.error) {
      window.$message?.success('头像已上传');
      await load();
    }
  } finally {
    uploading.value = false;
  }
}

function edit(row?: Row) {
  editing.value = !!row;
  Object.assign(form, row || { id: undefined, type: 0, sort: 0, status: 0 });
  open.value = true;
}

async function save() {
  if (!form.id) return;
  const r = await updateSystemAvatar({
    id: form.id,
    type: form.type,
    sort: form.sort,
    status: form.status
  });
  if (!r.error) {
    open.value = false;
    window.$message?.success('已保存');
    await load();
  }
}

async function toggle(row: Row, value: boolean) {
  await updateSystemAvatar({ id: row.id, status: value ? 0 : 1 });
  await load();
}

const columns: DataTableColumns<Row> = [
  {
    title: '预览',
    key: 'url',
    width: 90,
    render: r => h(NImage, { src: mediaUrl(r.url), width: 44, height: 44, objectFit: 'cover' })
  },
  {
    title: '类型',
    key: 'type',
    width: 110,
    render: r => h(NTag, { type: r.type === 1 ? 'success' : 'default', size: 'small' }, () => (r.type === 1 ? '动态' : '静态'))
  },
  { title: '地址', key: 'url', ellipsis: { tooltip: true } },
  { title: '排序', key: 'sort', width: 90 },
  {
    title: '启用',
    key: 'status',
    width: 90,
    render: r => h(NSwitch, { value: r.status === 0, 'onUpdate:value': (v: boolean) => toggle(r, v) })
  },
  {
    title: '操作',
    key: 'op',
    width: 160,
    render: r =>
      h(NSpace, {}, () => [
        h(NButton, { size: 'small', onClick: () => edit(r) }, () => '编辑'),
        h(
          NPopconfirm,
          {
            onPositiveClick: async () => {
              const x = await deleteSystemAvatar(r.id);
              if (!x.error) await load();
            }
          },
          { trigger: () => h(NButton, { size: 'small', type: 'error' }, () => '删除'), default: () => '删除后文件同步清除，是否继续？' }
        )
      ])
  }
];

onMounted(load);
</script>

<template>
  <div>
    <NCard title="系统头像" :bordered="false">
      <template #header-extra>
        <NSpace>
          <NSelect v-model:value="filterType" :options="filterOptions" class="w-140px" />
          <NButton type="primary" :loading="uploading" @click="pickFile">上传头像</NButton>
        </NSpace>
      </template>
      <input ref="fileInput" type="file" accept="image/*" class="hidden" @change="onFileChange" />
      <NDataTable :columns="columns" :data="filtered" :loading="loading" />
    </NCard>

    <NModal v-model:show="open" preset="card" :title="editing ? '编辑头像' : '新增头像'" class="w-600px max-w-95vw">
      <NForm label-placement="left" label-width="90">
        <NFormItem label="类型">
          <NSelect v-model:value="form.type" :options="typeOptions" />
        </NFormItem>
        <NFormItem label="排序">
          <NInputNumber v-model:value="form.sort" />
        </NFormItem>
        <NFormItem label="启用">
          <NSwitch :value="form.status === 0" @update:value="v => (form.status = v ? 0 : 1)" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="open = false">取消</NButton>
          <NButton type="primary" @click="save">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>
