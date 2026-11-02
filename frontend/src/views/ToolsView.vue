<template>
  <div class="tools-view">
    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="page-desc">当前已注册 {{ tools.length }} 个工具</span>
      <button class="refresh-btn" :class="{ spinning: loading }" @click="loadTools">
        <i class="fas fa-sync-alt" />
        刷新
      </button>
    </div>

    <!-- 分类过滤 -->
    <div class="category-tabs">
      <button
        v-for="cat in categories"
        :key="cat"
        class="cat-btn"
        :class="{ active: selectedCategory === cat }"
        @click="selectedCategory = cat"
      >
        {{ cat === 'all' ? '全部' : cat }}
        <span class="cat-count">
          {{ cat === 'all' ? tools.length : tools.filter(t => t.category === cat).length }}
        </span>
      </button>
    </div>

    <!-- 工具列表 -->
    <div v-if="filteredTools.length > 0" class="tools-grid">
      <div
        v-for="tool in filteredTools"
        :key="tool.name"
        class="tool-card"
      >
        <div class="tool-icon" :style="{ background: categoryColor(tool.category) }">
          <i :class="categoryIcon(tool.category)" />
        </div>
        <div class="tool-info">
          <div class="tool-header">
            <h3 class="tool-name">{{ tool.name }}</h3>
            <span class="tool-status" :class="tool.enabled ? 'enabled' : 'disabled'">
              {{ tool.enabled ? '可用' : '禁用' }}
            </span>
          </div>
          <p class="tool-description">{{ tool.description }}</p>
          <span class="tool-category-badge">{{ tool.category }}</span>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <EmptyState
        v-else-if="!loading"
        icon="fas fa-tools"
        title="暂无可用工具"
        sub="请检查后端服务是否正常运行"
      />

    <!-- 加载中 -->
    <SkeletonList v-if="loading" :rows="6" variant="cards" />

    <!-- API Key 和推理参数已移至「MCP 配置」页面 -->
    <div class="tools-footer-hint">
      <i class="fas fa-plug" />
      API Key 配置和推理参数调节已移至
      <router-link to="/admin/mcp" class="mcp-link">MCP 配置</router-link>
      页面
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getTools } from '@/services/api'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

const tools            = ref([])
const loading          = ref(false)
const selectedCategory = ref('all')

// ── 分类配置 ──────────────────────────────────────────────
const CATEGORY_CONFIG = {
  math:      { icon: 'fas fa-calculator',   color: 'var(--color-success)' },
  utility:   { icon: 'fas fa-clock',        color: 'var(--color-primary)' },
  file:      { icon: 'fas fa-folder-open',  color: 'var(--color-warn)' },
  web:       { icon: 'fas fa-globe',        color: 'var(--color-violet)' },
  system:    { icon: 'fas fa-server',       color: 'var(--color-info)' },
  memory:    { icon: 'fas fa-brain',        color: 'var(--color-danger)' },
  scheduler: { icon: 'fas fa-calendar-alt', color: 'var(--color-warn)' },
  general:   { icon: 'fas fa-wrench',       color: 'var(--color-text-secondary)' },
}

const categoryIcon  = (cat) => CATEGORY_CONFIG[cat]?.icon  || 'fas fa-wrench'
const categoryColor = (cat) => CATEGORY_CONFIG[cat]?.color || 'var(--color-text-secondary)'

// ── 计算属性 ──────────────────────────────────────────────
const categories = computed(() => {
  const cats = [...new Set(tools.value.map(t => t.category))]
  return ['all', ...cats]
})

const filteredTools = computed(() =>
  selectedCategory.value === 'all'
    ? tools.value
    : tools.value.filter(t => t.category === selectedCategory.value)
)

// ── 数据加载 ──────────────────────────────────────────────
const loadTools = async () => {
  loading.value = true
  try {
    const data = await getTools()
    if (data?.tools) {
      tools.value = data.tools
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadTools)
</script>

<style scoped>
.tools-view {
  height: 100%;
  padding: var(--space-4-5);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-3-5);
  background: var(--color-surface-sunken);
}

/* ── 工具栏 ─────────────────────────────────────────────── */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-desc { font-size: var(--text-sm); color: var(--color-text-secondary); }
.refresh-btn {
  display: flex; align-items: center; gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-3-5); border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  font-size: var(--text-sm); color: var(--color-text); cursor: pointer;
  transition: var(--transition-interactive);
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ── 分类标签 ────────────────────────────────────────────── */
.category-tabs {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.cat-btn {
  display: flex; align-items: center; gap: var(--space-1);
  padding: var(--space-1-5) var(--space-3); border-radius: var(--radius-lg);
  border: 1px solid var(--color-border); background: var(--color-surface);
  font-size: var(--text-sm); color: var(--color-text-secondary); cursor: pointer;
  transition: var(--transition-interactive);
}
.cat-btn:hover  { border-color: var(--color-primary); color: var(--color-primary); }
.cat-btn.active { background: var(--color-primary); border-color: var(--color-primary); color: var(--color-text-on-primary); }
.cat-count {
  background: var(--overlay-dark-subtle);
  padding: var(--space-px) var(--space-1-5); border-radius: var(--radius-sm);
  font-size: var(--text-xs);
}
.cat-btn.active .cat-count { background: var(--color-surface-on-dark-strong); }

/* ── 工具网格 ────────────────────────────────────────────── */
.tools-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--space-3-5);
}

/* ── 工具卡片 ────────────────────────────────────────────── */
.tool-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  display: flex;
  align-items: flex-start;
  gap: var(--space-3-5);
  transition: var(--transition-interactive);
  min-height: 118px; /* 与两行描述 + 头部一致的视觉高度，避免行内卡片参差 */

  box-shadow: var(--shadow-card);}
.tool-card:hover {
  border-color: var(--color-primary-soft);
  box-shadow: var(--shadow-glow-primary);
  transform: translateY(-1px);
}

.tool-icon {
  width: 44px; height: 44px;
  border-radius: var(--radius-sm);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-xl); color: white;
  flex-shrink: 0;
}

.tool-info  { flex: 1; min-width: 0; }

.tool-header {
  display: flex; align-items: center;
  justify-content: space-between;
  gap: var(--space-2); margin-bottom: var(--space-1-5);
}
.tool-name {
  font-size: var(--text-base); font-weight: var(--weight-medium);
  color: var(--color-text); margin: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.tool-status {
  font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2);
  border-radius: var(--radius-sm); font-weight: var(--weight-medium); flex-shrink: 0;
}
.tool-status.enabled  { background: var(--color-success-soft); color: var(--color-success); }
.tool-status.disabled { background: var(--color-danger-soft); color: var(--color-danger); }

.tool-description {
  font-size: var(--text-sm-2); color: var(--color-text-secondary);
  line-height: var(--leading-normal); margin: 0 0 var(--space-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tool-category-badge {
  font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2);
  background: var(--color-surface-raised); color: var(--color-primary);
  border-radius: var(--radius-xs);
}

/* ── 空状态 ───────────────────────────────────────────────── */

/* ── 加载中 ───────────────────────────────────────────────── */

/* ── 配置面板 ─────────────────────────────────────────────── */
.config-card {
  background: var(--color-surface); border-radius: var(--radius-md);
  border: 0.5px solid var(--color-border); padding: var(--space-4);

  box-shadow: var(--shadow-card);}
.config-title {
  font-size: var(--text-base); font-weight: var(--weight-medium); color: var(--color-text);
  display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-1);
}
.config-title i { color: var(--color-primary); }
.config-hint { font-size: var(--text-sm-2); color: var(--color-text-muted); margin-bottom: var(--space-3-5); }
.config-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3-5); }
.config-item { display: flex; flex-direction: column; gap: var(--space-1); }
.cfg-label { font-size: var(--text-sm); color: var(--color-text); font-weight: var(--weight-medium); }
.cfg-input-row { display: flex; gap: var(--space-1-5); align-items: center; }
.cfg-input {
  flex: 1; padding: var(--space-2) var(--space-3); border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); font-size: var(--text-sm); outline: none;
  transition: border-color var(--duration-base);
}
.cfg-input:focus { border-color: var(--color-primary); }
.cfg-eye {
  padding: var(--space-2) var(--space-2-5); border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer;
}
.cfg-desc { font-size: var(--text-xs); color: var(--color-text-muted); }
.config-footer { display: flex; align-items: center; gap: var(--space-3); margin-top: var(--space-3-5); }
.cfg-save-btn {
  display: flex; align-items: center; gap: var(--space-1-5);
  padding: var(--space-2) var(--space-4); border-radius: var(--radius-sm);
  border: none; background: var(--color-primary); color: var(--color-text-on-primary);
  font-size: var(--text-sm); cursor: pointer; transition: background var(--duration-base);
}
.cfg-save-btn:hover:not(:disabled) { background: var(--color-primary-hover); }
.cfg-save-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.cfg-tip { font-size: var(--text-xs); color: var(--color-text-muted); }

/* ── 参数调节 ─────────────────────────────────────────────── */
.param-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--space-4); }
.param-item { display: flex; flex-direction: column; gap: var(--space-1-5); }
.param-item label { font-size: var(--text-sm); color: var(--color-text); font-weight: var(--weight-medium); display: flex; align-items: center; gap: var(--space-2); }
.param-val { background: var(--color-surface-raised); color: var(--color-primary); padding: var(--space-px) var(--space-2); border-radius: var(--radius-sm); font-size: var(--text-xs); }
.param-slider { width: 100%; accent-color: var(--color-primary); }
.param-range { display: flex; justify-content: space-between; font-size: var(--text-xs); color: var(--color-text-muted); }

/* ── MCP 跳转提示 ─────────────────────────────────────────── */
.tools-footer-hint {
  font-size: var(--text-sm-2);
  color: var(--color-text-muted);
  padding: var(--space-3-5) var(--space-4);
  background: var(--color-surface);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.tools-footer-hint i { color: var(--color-primary); }
.mcp-link {
  color: var(--color-primary);
  font-weight: var(--weight-medium);
  text-decoration: none;
}
.mcp-link:hover { text-decoration: underline; }

@media (max-width: 768px) {
  .config-grid { grid-template-columns: 1fr; }
  .param-grid  { grid-template-columns: 1fr; }
}
</style>
