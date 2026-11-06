<script setup lang="ts">
import { VueDraggable } from 'vue-draggable-plus';

defineOptions({ name: 'DragSortList' });

const model = defineModel<any[]>({ required: true });
const emit = defineEmits<{ reorder: [] }>();

function handleReorder() {
  emit('reorder');
}
</script>

<template>
  <div class="drag-sort-list">
    <NEmpty v-if="!model.length" class="py-32px" description="暂无数据" />
    <VueDraggable
      v-else
      v-model="model"
      :animation="200"
      handle=".drag-sort-handle"
      ghost-class="drag-sort-ghost"
      chosen-class="drag-sort-chosen"
      @update="handleReorder"
    >
      <div v-for="(item, index) in model" :key="item.id" class="drag-sort-item">
        <span class="drag-sort-handle" title="拖动调整顺序" @click.stop>
          <svg viewBox="0 0 10 16" width="10" height="16" fill="currentColor" aria-hidden="true">
            <circle cx="2" cy="2" r="1.5" />
            <circle cx="8" cy="2" r="1.5" />
            <circle cx="2" cy="8" r="1.5" />
            <circle cx="8" cy="8" r="1.5" />
            <circle cx="2" cy="14" r="1.5" />
            <circle cx="8" cy="14" r="1.5" />
          </svg>
        </span>
        <span class="drag-sort-index">{{ index + 1 }}</span>
        <div class="drag-sort-content">
          <slot name="header" :item="item" :index="index" />
        </div>
        <div class="drag-sort-actions" @click.stop>
          <slot name="actions" :item="item" :index="index" />
        </div>
      </div>
    </VueDraggable>
  </div>
</template>

<style scoped>
.drag-sort-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  margin-bottom: 8px;
  border: 1px solid rgba(128, 128, 128, 0.22);
  border-radius: 6px;
  transition: box-shadow 0.2s ease;
}

.drag-sort-item:last-child {
  margin-bottom: 0;
}

.drag-sort-item:hover {
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.08);
}

.drag-sort-ghost {
  opacity: 0.4;
}

.drag-sort-chosen {
  cursor: grabbing;
}

.drag-sort-handle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  color: rgba(128, 128, 128, 0.55);
  cursor: grab;
  flex-shrink: 0;
}

.drag-sort-handle:hover {
  color: var(--primary-color, #18a058);
}

.drag-sort-handle:active {
  cursor: grabbing;
}

.drag-sort-index {
  min-width: 20px;
  font-size: 12px;
  color: rgba(128, 128, 128, 0.85);
  text-align: center;
  flex-shrink: 0;
}

.drag-sort-content {
  flex: 1;
  min-width: 0;
}

.drag-sort-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
