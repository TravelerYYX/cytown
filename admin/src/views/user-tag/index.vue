<script setup lang="ts">
import { h, onMounted, reactive, ref } from 'vue';
import { NButton, NPopconfirm, NSpace, NSwitch, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { createUserTag, deleteUserTag, fetchUserTagList, updateUserTag } from '@/service/api';

defineOptions({ name: 'UserTag' });

type Row = Api.P6.UserTag;

const rows = ref<Row[]>([]);
const loading = ref(false);
const open = ref(false);
const editing = ref(false);
const form = reactive<Partial<Row>>({ id: undefined, name: '', category: '', sort: 0, status: 0 });

async function load() {
  loading.value = true;
  try {
    const r = await fetchUserTagList();
    if (r.data) rows.value = r.data;
  } finally {
    loading.value = false;
  }
}

function edit(row?: Row) {
  editing.value = !!row;
  Object.assign(form, row || { id: undefined, name: '', category: '', sort: 0, status: 0 });
  open.value = true;
}

async function save() {
  if (!form.name?.trim()) {
    window.$message?.warning('请输入标签名称');
    return;
  }
  if (!form.category?.trim()) {
    window.$message?.warning('请输入标签分类');
    return;
  }
  const payload = {
    id: form.id as number,
    name: form.name.trim(),
    category: form.category.trim(),
    sort: form.sort,
    status: form.status
  };
  const r = editing.value ? await updateUserTag(payload) : await createUserTag(payload);
  if (!r.error) {
    open.value = false;
    window.$message?.success('标签已保存');
    await load();
  }
}

async function toggle(row: Row, value: boolean) {
  await updateUserTag({ id: row.id, status: value ? 0 : 1 });
  await load();
}

const columns: DataTableColumns<Row> = [
  { title: '名称', key: 'name', width: 160 },
  { title: '分类', key: 'category', width: 140 },
  {
    title: '来源',
    key: 'source',
    width: 100,
    render: r => h(NTag, { size: 'small', type: r.source === 1 ? 'info' : 'default' }, () => (r.source === 1 ? '自定义' : '系统'))
  },
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
              const x = await deleteUserTag(r.id);
              if (!x.error) await load();
            }
          },
          { trigger: () => h(NButton, { size: 'small', type: 'error' }, () => '删除'), default: () => '会同时移除用户已选的该标签，是否继续？' }
        )
      ])
  }
];

onMounted(load);
</script>

<template>
  <div>
    <NCard title="用户标签库" :bordered="false">
      <template #header-extra>
        <NButton type="primary" @click="edit()">新增标签</NButton>
      </template>
      <NDataTable :columns="columns" :data="rows" :loading="loading" />
    </NCard>

    <NModal v-model:show="open" preset="card" :title="editing ? '编辑标签' : '新增标签'" class="w-600px max-w-95vw">
      <NForm label-placement="left" label-width="90">
        <NFormItem label="名称">
          <NInput v-model:value="form.name" maxlength="20" />
        </NFormItem>
        <NFormItem label="分类">
          <NInput v-model:value="form.category" maxlength="20" placeholder="如 游戏 / 本体 / 兴趣 / MBTI" />
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
