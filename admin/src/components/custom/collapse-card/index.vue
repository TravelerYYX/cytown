<script setup lang="ts">
import { ref } from 'vue';

defineOptions({ name: 'CollapseCard' });

const props = withDefaults(defineProps<{ title?: string; defaultOpen?: boolean }>(), {
  title: '',
  defaultOpen: true
});

const open = ref(props.defaultOpen);
</script>

<template>
  <NCard :bordered="false">
    <template #header>
      <div class="collapse-card-header" @click="open = !open">
        <span class="collapse-card-arrow" :class="{ 'is-collapsed': !open }">
          <svg
            viewBox="0 0 16 16"
            width="14"
            height="14"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M4 6l4 4 4-4" />
          </svg>
        </span>
        <span class="collapse-card-title">{{ title }}</span>
      </div>
    </template>
    <template v-if="$slots.extra" #header-extra>
      <div class="collapse-card-extra" @click.stop>
        <slot name="extra" />
      </div>
    </template>
    <div v-show="open" class="collapse-card-body">
      <slot />
    </div>
  </NCard>
</template>

<style scoped>
.collapse-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  cursor: pointer;
  user-select: none;
}

.collapse-card-arrow {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: rgba(128, 128, 128, 0.75);
  transition: transform 0.2s ease;
  flex-shrink: 0;
}

.collapse-card-arrow.is-collapsed {
  transform: rotate(-90deg);
}

.collapse-card-title {
  font-weight: 500;
}
</style>
