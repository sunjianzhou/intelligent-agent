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
      <div class="suggestion-card" @click="emit('suggest', '帮我计算 (23 * 45) + sqrt(169) 的结果')">
        <i class="fas fa-calculator suggestion-icon" />
        <span class="suggestion-label">数学计算</span>
        <span class="suggestion-text">帮我计算 (23 × 45) + √169</span>
      </div>
      <div class="suggestion-card" @click="emit('suggest', '现在几点了？今天是星期几？')">
        <i class="fas fa-clock suggestion-icon" />
        <span class="suggestion-label">时间查询</span>
        <span class="suggestion-text">现在几点？今天星期几？</span>
      </div>
      <div class="suggestion-card" @click="emit('suggest', '帮我写一首关于秋天的五言绝句')">
        <i class="fas fa-pen-nib suggestion-icon" />
        <span class="suggestion-label">创意写作</span>
        <span class="suggestion-text">写一首关于秋天的五言绝句</span>
      </div>
      <div class="suggestion-card" @click="emit('suggest', '每隔30分钟提醒我喝水，帮我创建周期性任务')">
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
  gap: 20px;
  padding: 40px var(--space-5);
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
.empty-icon  { font-size: 2.8rem; color: #c5c8f0; }
.empty-title { font-size: 1.25rem; font-weight: 600; color: #444; margin: 0; }
.empty-sub   { font-size: 0.88rem; color: var(--color-text-muted); margin: 0; }
.uncensored-badge-row { margin-top: 6px; display: flex; justify-content: center; }
.uncensored-badge {
  background: linear-gradient(135deg, #f6d365, #fda085);
  color: white;
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px var(--space-2);
  border-radius: 20px;
  font-size: 0.78rem;
  font-weight: 600;
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
  gap: 2px 10px;
  padding: 14px var(--space-4);
  border: 1px solid #e8eaf0;
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
  transition: all 0.18s;
  align-items: center;
}
/* 4 张卡片各自颜色主题 */
.suggestion-card:nth-child(1) { background: #eff6ff; border-color: #bfdbfe; }
.suggestion-card:nth-child(2) { background: #fff7ed; border-color: #fed7aa; }
.suggestion-card:nth-child(3) { background: #f0fdf4; border-color: #bbf7d0; }
.suggestion-card:nth-child(4) { background: #faf5ff; border-color: #e9d5ff; }

.suggestion-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
}
.suggestion-card:nth-child(1):hover { border-color: #3b82f6; }
.suggestion-card:nth-child(2):hover { border-color: #f97316; }
.suggestion-card:nth-child(3):hover { border-color: #22c55e; }
.suggestion-card:nth-child(4):hover { border-color: #a855f7; }

.suggestion-icon {
  grid-area: icon;
  font-size: 1.15rem;
  justify-self: center;
}
.suggestion-card:nth-child(1) .suggestion-icon { color: #3b82f6; }
.suggestion-card:nth-child(2) .suggestion-icon { color: #f97316; }
.suggestion-card:nth-child(3) .suggestion-icon { color: #22c55e; }
.suggestion-card:nth-child(4) .suggestion-icon { color: #a855f7; }
.suggestion-label {
  grid-area: label;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.suggestion-text {
  grid-area: text;
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.empty-notice {
  font-size: 0.78rem;
  color: var(--color-text-muted);
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0;
}
@media (max-width: 600px) {
  .suggestion-grid { grid-template-columns: 1fr; }
}
</style>
