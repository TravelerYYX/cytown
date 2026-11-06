<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import type { UploadCustomRequestOptions } from 'naive-ui';
import { useAuthStore } from '@/store/modules/auth';
import { mediaUrl } from '@/utils/service';
import { fetchLevelConfig, saveLevelConfig, uploadLevelBadge } from '@/service/api/level';
import type { LevelConfig, LevelItem } from '@/service/api/level';

defineOptions({ name: 'LevelSettings' });
const auth = useAuthStore();
const config = ref<LevelConfig>();
const loading = ref(false), saving = ref(false), error = ref(''), baseline = ref('');
const dirty = computed(() => config.value != null && JSON.stringify(config.value) !== baseline.value);
const selectedTier = ref(1);
const uploadingLevel = ref<number | null>(null);

const visibleLevels = computed(() => {
  const tier = config.value?.tiers.find(item => item.id === selectedTier.value);
  return config.value?.levels.filter(item => !tier || (item.level >= tier.minLevel && item.level <= tier.maxLevel)) || [];
});

async function load(force = false) {
  if (!force && dirty.value && !window.confirm('有未保存的等级配置，确定重新加载并放弃修改？')) return;
  loading.value = true;
  error.value = '';
  try {
    const r = await fetchLevelConfig();
    if (r.error) {
      error.value = '加载失败，请重试';
      return;
    }
    baseline.value = JSON.stringify(r.data);
    config.value = r.data;
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!config.value || saving.value) return;
  saving.value = true;
  try {
    const r = await saveLevelConfig(config.value);
    if (!r.error) {
      baseline.value = JSON.stringify(r.data);
      config.value = r.data;
      error.value = '';
      window.$message?.success('等级配置已保存，现有用户等级已按累计经验重新计算');
    } else {
      error.value = '保存失败，修改已保留；如配置已被更新，请重新加载后编辑';
    }
  } finally {
    saving.value = false;
  }
}

async function handleBadgeUpload({ file, onFinish, onError }: UploadCustomRequestOptions, level: LevelItem) {
  if (!file.file) return;
  if (file.file.type !== 'image/png') {
    window.$message?.error('请选择透明 PNG 格式的徽章图片');
    onError();
    return;
  }
  if (file.file.size > 2 * 1024 * 1024) {
    window.$message?.error('徽章图片大小不能超过 2MB');
    onError();
    return;
  }
  uploadingLevel.value = level.level;
  try {
    const r = await uploadLevelBadge(file.file);
    if (!r.error && r.data?.url) {
      level.badgeUrl = r.data.url;
      window.$message?.success(`Lv.${level.level} 徽章上传成功`);
      onFinish();
    } else {
      window.$message?.error('徽章上传失败，请重试');
      onError();
    }
  } catch {
    window.$message?.error('上传出现异常，请稍后重试');
    onError();
  } finally {
    uploadingLevel.value = null;
  }
}

onMounted(() => load(true));
onBeforeRouteLeave(() => !dirty.value || window.confirm('有未保存的等级配置，确定离开并放弃修改？'));
</script>

<template>
  <NSpace vertical :size="16">
    <NCard title="社区等级" :bordered="false">
      <NSpace justify="space-between" align="center">
        <div>60级 · 六个段位 · 签到与互动成长 <NTag v-if="dirty" type="warning" size="small">有未保存修改</NTag></div>
        <NSpace><NButton :disabled="saving" :loading="loading" @click="load()">重新加载</NButton><NButton type="primary" :disabled="auth.isReadOnly || !config || loading" :loading="saving" @click="save">保存全部配置</NButton></NSpace>
      </NSpace>
      <NAlert class="mt-16px" type="info" :show-icon="false">经验为累计积分。修改门槛会重新计算现有等级；不会补发历史跨段奖励。每日限额按北京时间重置，填0关闭该行为奖励；连续签到每满7天额外奖励一次。待审核内容在首次通过审核时奖励。</NAlert>
      <NAlert v-if="error" type="error" class="mt-16px">{{ error }}</NAlert>
    </NCard>
    <template v-if="config">
      <NCard title="段位与头衔" :bordered="false">
        <NForm :disabled="auth.isReadOnly || saving">
          <div class="table-scroll"><table class="level-table"><thead><tr><th>阶数</th><th>等级区间</th><th>头衔 / 拓展全称</th><th>色系</th><th>定位说明</th><th>进入此段奖励经验</th></tr></thead>
            <tbody><tr v-for="tier in config.tiers" :key="tier.id">
              <td>{{ tier.id }}阶</td><td><div class="range"><NInputNumber v-model:value="tier.minLevel" :min="1" :max="60" :precision="0" :show-button="false" /><span>至</span><NInputNumber v-model:value="tier.maxLevel" :min="1" :max="60" :precision="0" :show-button="false" /></div></td>
              <td><NSpace vertical><NInput v-model:value="tier.title" :maxlength="20" /><NInput v-model:value="tier.fullTitle" :maxlength="60" /></NSpace></td>
              <td><NColorPicker v-model:value="tier.color" :show-alpha="false" :modes="['hex']" /></td>
              <td><NInput v-model:value="tier.description" type="textarea" :maxlength="500" /></td>
              <td><NInputNumber v-model:value="tier.entryBonus" :min="0" :max="1000000" :precision="0" :disabled="tier.id === 1" /></td>
            </tr></tbody></table></div>
        </NForm>
      </NCard>
      <NCard title="经验获取规则" :bordered="false"><NAlert type="info" class="mb-16px">每日签到经验由运营管理 → 签到管理的逐日奖励统一配置，连续7天额外经验在此配置。</NAlert>
        <NForm :disabled="auth.isReadOnly || saving">
          <div class="table-scroll"><table class="level-table rules-table"><thead><tr><th>行为名称</th><th>每次经验</th><th>每日经验上限</th><th>启用</th></tr></thead>
            <tbody><tr v-for="rule in config.rules.filter(r=>r.action!=='sign_in')" :key="rule.action"><td><NInput v-model:value="rule.name" :maxlength="40" /></td><td><NInputNumber v-model:value="rule.experience" :min="0" :max="1000000" :precision="0" /></td><td><NInputNumber v-model:value="rule.dailyLimit" :min="0" :max="10000000" :precision="0" /></td><td><NSwitch v-model:value="rule.enabled" /></td></tr></tbody>
          </table></div>
        </NForm>
      </NCard>
      <NCard title="等级门槛与徽章" :bordered="false">
        <NTabs v-model:value="selectedTier" type="segment"><NTab v-for="tier in config.tiers" :key="tier.id" :name="tier.id" :tab="tier.title + ' Lv.' + tier.minLevel + '–' + tier.maxLevel" /></NTabs>
        <NForm :disabled="auth.isReadOnly || saving">
          <div class="table-scroll">
            <table class="level-table badge-table">
              <thead>
                <tr>
                  <th style="width: 70px; text-align: center;">等级</th>
                  <th style="width: 90px; text-align: center;">徽章预览</th>
                  <th style="width: 170px;">所需累计经验</th>
                  <th>徽章图片地址 / 上传 PNG</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="level in visibleLevels" :key="level.level">
                  <td class="text-center font-bold text-primary">Lv.{{ level.level }}</td>
                  <td>
                    <div class="badge-preview">
                      <NImage
                        v-if="level.badgeUrl"
                        :src="mediaUrl(level.badgeUrl)"
                        :width="64"
                        :height="64"
                        object-fit="contain"
                      />
                      <div v-else class="badge-empty">
                        <SvgIcon icon="ant-design:picture-outlined" class="text-20px text-gray-400" />
                        <span class="text-11px text-gray-400 mt-2px">暂无</span>
                      </div>
                    </div>
                  </td>
                  <td>
                    <NInputNumber
                      v-model:value="level.requiredExp"
                      :min="0"
                      :max="1000000000000"
                      :precision="0"
                      :disabled="level.level === 1"
                    />
                  </td>
                  <td>
                    <div class="badge-col">
                      <NInput
                        v-model:value="level.badgeUrl"
                        placeholder="输入图片 URL 或点击下方上传"
                        :maxlength="2048"
                        clearable
                        size="small"
                        class="w-full"
                      >
                        <template #prefix>
                          <SvgIcon icon="ant-design:link-outlined" class="text-gray-400 text-14px" />
                        </template>
                      </NInput>
                      <div class="flex items-center justify-between gap-8px mt-8px">
                        <NUpload
                          accept="image/png"
                          :show-file-list="false"
                          :disabled="auth.isReadOnly || saving || uploadingLevel === level.level"
                          :custom-request="(options) => handleBadgeUpload(options, level)"
                        >
                          <NButton
                            type="primary"
                            secondary
                            size="small"
                            :loading="uploadingLevel === level.level"
                            :disabled="auth.isReadOnly || saving"
                          >
                            <template #icon>
                              <SvgIcon icon="ant-design:cloud-upload-outlined" />
                            </template>
                            上传 PNG 徽章
                          </NButton>
                        </NUpload>
                        <div class="badge-tip">
                          <SvgIcon icon="ant-design:info-circle-outlined" class="text-12px inline-block mr-4px" />
                          <span>支持 2MB 以内透明 PNG</span>
                        </div>
                      </div>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </NForm>
      </NCard>
    </template>
  </NSpace>
</template>

<style scoped>
.table-scroll {
  overflow-x: auto;
}
.level-table {
  width: 100%;
  min-width: 900px;
  border-collapse: collapse;
  text-align: left;
}
.level-table th,
.level-table td {
  padding: 12px 10px;
  border-bottom: 1px solid var(--n-border-color, #eee);
  vertical-align: middle;
}
.level-table th {
  color: #808692;
  font-weight: 500;
  white-space: nowrap;
}
.level-table td:nth-child(2) {
  min-width: 180px;
}
.level-table td:nth-child(3) {
  min-width: 200px;
}
.level-table td:nth-child(4) {
  min-width: 120px;
}
.range {
  display: flex;
  align-items: center;
  gap: 8px;
}
.range :deep(.n-input-number) {
  width: 80px;
}
.rules-table {
  min-width: 650px;
}

/* 徽章配置专用样式 */
.badge-table {
  width: 100%;
  min-width: 800px;
}
.badge-table th:first-child,
.badge-table td:first-child {
  text-align: center;
}
.badge-table th:nth-child(2),
.badge-table td:nth-child(2) {
  text-align: center;
}
.badge-table td:nth-child(2) {
  min-width: 90px;
}
.badge-table td:nth-child(3) {
  min-width: 170px;
}
.badge-table td:nth-child(4) {
  min-width: 380px;
}
.badge-preview {
  width: 64px;
  height: 64px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  background: repeating-conic-gradient(#edf0f4 0% 25%, #fff 0% 50%) 0 0/12px 12px;
  border: 1px solid var(--n-border-color, #eee);
  border-radius: 8px;
  overflow: hidden;
}
.badge-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.badge-col {
  width: 100%;
  display: flex;
  flex-direction: column;
}
.badge-tip {
  font-size: 12px;
  color: var(--n-text-color-3, #999);
  display: flex;
  align-items: center;
  white-space: nowrap;
}
</style>
