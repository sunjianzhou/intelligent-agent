<template>
  <div class="empty-state">
    <div class="empty-hero">
      <i class="fas fa-robot empty-icon"></i>
      <h2 class="empty-title">你好，我是智能助手</h2>
      <p class="empty-sub">本地 AI · 私有部署 · 支持工具调用</p>
      <div v-if="modelStatus?.includes('dolphin')" class="uncensored-badge-row">
        <span class="uncensored-badge"><i class="fas fa-lock-open" />无限制模式</span>
      </div>
    </div>

    <!-- 示例提示词卡片 -->
    <div class="suggestion-grid">
      <div
        class="suggestion-card"
        role="button"
        tabindex="0"
        @click="emit('suggest', '帮我计算 (23 * 45) + sqrt(169) 的结果')"
        @keydown.enter.prevent="emit('suggest', '帮我计算 (23 * 45) + sqrt(169) 的结果')"
        @keydown.space.prevent="emit('suggest', '帮我计算 (23 * 45) + sqrt(169) 的结果')"
      >
        <i class="fas fa-calculator suggestion-icon" />
        <span class="suggestion-label">数学计算</span>
        <span class="suggestion-text">帮我计算 (23 × 45) + √169</span>
      </div>
      <div
        class="suggestion-card"
        role="button"
        tabindex="0"
        @click="emit('suggest', '现在几点了？今天是星期几？')"
        @keydown.enter.prevent="emit('suggest', '现在几点了？今天是星期几？')"
        @keydown.space.prevent="emit('suggest', '现在几点了？今天是星期几？')"
      >
        <i class="fas fa-clock suggestion-icon" />
        <span class="suggestion-label">时间查询</span>
        <span class="suggestion-text">现在几点？今天星期几？</span>
      </div>
      <div
        class="suggestion-card"
        role="button"
        tabindex="0"
        @click="emit('suggest', '帮我写一首关于秋天的五言绝句')"
        @keydown.enter.prevent="emit('suggest', '帮我写一首关于秋天的五言绝句')"
        @keydown.space.prevent="emit('suggest', '帮我写一首关于秋天的五言绝句')"
      >
        <i class="fas fa-pen-nib suggestion-icon" />
        <span class="suggestion-label">创意写作</span>
        <span class="suggestion-text">写一首关于秋天的五言绝句</span>
      </div>
      <div
        class="suggestion-card"
        role="button"
        tabindex="0"
        @click="emit('suggest', '每隔30分钟提醒我喝水，帮我创建周期性任务')"
        @keydown.enter.prevent="emit('suggest', '每隔30分钟提醒我喝水，帮我创建周期性任务')"
        @keydown.space.prevent="emit('suggest', '每隔30分钟提醒我喝水，帮我创建周期性任务')"
      >
        <i class="fas fa-bell suggestion-icon" />
        <span class="suggestion-label">定时提醒</span>
        <span class="suggestion-text">每隔30分钟提醒我喝水</span>
      </div>
    </div>

    <!-- 等待时间说明 -->
    <p class="empty-notice">
      <i class="fas fa-info-circle" />
      <template v-if="isCloudMode">云端模型推理中，响应速度取决于网络状况</template>
      <template v-else>本地 CPU 推理通常需要 60～300 秒，请耐心等待</template>
    </p>
  </div>
</template>

<script setup>
defineProps({
  modelStatus: String,
  isCloudMode: Boolean,
})
const emit = defineEmits(['suggest'])
</script>

<style scoped>
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-4-5);
  padding: var(--space-7) var(--space-5);
  max-width: 700px;
  margin: 0 auto;
  width: 100%;
}
.empty-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
}
.empty-icon  { font-size: 2.8rem; color: var(--color-primary); opacity: 0.85; }
.empty-title { font-size: var(--text-2xl); font-weight: var(--weight-semibold); color: var(--color-text); margin: 0; }
.empty-sub   { font-size: var(--text-sm); color: var(--color-text-muted); margin: 0; }
.uncensored-badge-row { margin-top: var(--space-1); display: flex; justify-content: center; }
.uncensored-badge {
  background: var(--color-warn-soft);
  color: var(--color-warn);
  display: inline-flex; align-items: center; gap: var(--space-1);
  padding: var(--space-0-5) var(--space-2);
  border-radius: var(--radius-full);
  font-size: var(--text-2xs);
  font-weight: var(--weight-semibold);
}

/* 示例提示词卡片 */
.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-3);
  width: 100%;
}
.suggestion-card {
  display: grid;
  grid-template-areas: "icon label" "icon text";
  grid-template-columns: 36px 1fr;
  gap: var(--space-0-5) var(--space-2);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
  transition: var(--transition-interactive);
  align-items: center;
}
/* 每张卡片一个强调色：用令牌表达，明暗主题都会跟着切换
   （旧实现是 4 组写死的粉彩色，深色主题下会变成黑底上的 4 块亮色） */
.suggestion-card:nth-child(1) { --card-accent: var(--color-primary); --card-accent-soft: var(--color-primary-soft); }
.suggestion-card:nth-child(2) { --card-accent: var(--color-warn);    --card-accent-soft: var(--color-warn-soft); }
.suggestion-card:nth-child(3) { --card-accent: var(--color-success); --card-accent-soft: var(--color-success-soft); }
.suggestion-card:nth-child(4) { --card-accent: var(--color-violet);  --card-accent-soft: var(--color-violet-soft); }

.suggestion-card:hover,
.suggestion-card:focus-visible {
  transform: translateY(-2px);
  box-shadow: var(--shadow-raised);
  border-color: var(--card-accent);
  background: var(--card-accent-soft);
}

.suggestion-icon {
  grid-area: icon;
  font-size: 1.15rem;
  justify-self: center;
  color: var(--card-accent);
}
.suggestion-label {
  grid-area: label;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  font-weight: var(--weight-medium);
  text-transform: uppercase;
  letter-spacing: var(--tracking-wide);
}
.suggestion-text {
  grid-area: text;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.empty-notice {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin: 0;
}
@media (max-width: 600px) {
  .suggestion-grid { grid-template-columns: 1fr; }
}
</style>
