<template>
  <div class="role-editor">
    <!-- 顶部工具栏 -->
    <div class="editor-header">
      <el-select v-model="currentRoleId" placeholder="选择角色" style="width:220px" @change="onRoleSelect">
        <el-option
          v-for="r in roleList"
          :key="r.roleId"
          :label="r.roleCard?.name || r.roleId"
          :value="r.roleId"
        />
        <el-option value="__new__" label="＋ 新建角色" />
      </el-select>

      <div class="header-actions">
        <!-- 激活状态标识 -->
        <el-tag
          v-if="isActiveRole"
          type="success"
          effect="dark"
          size="small"
        >
          <i class="fas fa-circle" style="font-size:8px;margin-right:4px" />
          已激活
        </el-tag>

        <el-tooltip content="开启后保存时同步到后端 API">
          <el-switch v-model="syncEnabled" active-text="同步后端" inactive-text="仅本地" />
        </el-tooltip>
        <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
        <el-button
          v-if="!isActiveRole"
          type="primary" plain
          :disabled="!currentRoleId || currentRoleId === '__new__'"
          :loading="activating"
          @click="handleActivate"
        >激活角色</el-button>
        <el-button
          v-else
          plain
          @click="handleDeactivate"
        >停用</el-button>
        <el-button
          type="danger"
          :disabled="!currentRoleId || currentRoleId === '__new__'"
          @click="handleDelete"
        >删除</el-button>
      </div>
    </div>

    <!-- 五 Tab 编辑器 -->
    <el-tabs v-model="activeTab" class="editor-tabs">

      <!-- ── Tab 1：角色名片 ── -->
      <el-tab-pane label="角色名片" name="card">
        <el-alert type="info" :closable="false" class="card-guide-alert">
          <template #title>
            <span style="font-weight:500">快速上手</span>
          </template>
          <div class="card-guide-body">
            <span>填写<b>名片</b>后，再到「<b>核心身份</b> / <b>行为风格</b>」配置 AI 性格与回答方式。</span>
          </div>
        </el-alert>
        <el-form :model="form.roleCard" label-width="90px" style="margin-top:12px">
          <el-form-item label="角色名称" required>
            <el-input v-model="form.roleCard.name" placeholder="如：技术顾问 / 写作助手 / 健身教练" />
          </el-form-item>
          <el-form-item label="头像">
            <div class="avatar-row">
              <el-avatar :size="72" :src="form.roleCard.avatarUrl || undefined" shape="circle">
                {{ form.roleCard.name?.[0] || '?' }}
              </el-avatar>
              <el-button size="small" style="margin-left:12px" @click="triggerAvatarUpload">
                上传头像
              </el-button>
              <input
                ref="avatarInputEl"
                type="file"
                accept="image/*"
                style="display:none"
                @change="onAvatarChange"
              />
            </div>
          </el-form-item>
          <el-form-item label="签名">
            <el-input v-model="form.roleCard.signature" placeholder="如：帮你把想法变成清晰的文字" />
          </el-form-item>
          <el-form-item label="标签">
            <TagInput v-model="form.roleCard.tags" placeholder="输入标签后回车，如：编程、创作、效率" />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ── Tab 2：核心身份 ── -->
      <el-tab-pane label="核心身份" name="identity">
        <el-form :model="form.coreIdentity" label-width="90px">
          <el-form-item label="性格标签">
            <TagInput v-model="form.coreIdentity.personality" placeholder="如：温柔、理性…" />
            <div class="field-hint"><i class="fas fa-keyboard" /> 输入标签后按 <kbd>Enter</kbd> 添加，点击 × 删除</div>
          </el-form-item>

          <el-form-item label="核心原则">
            <div class="list-editor">
              <div
                v-for="(_, i) in form.coreIdentity.principles"
                :key="i"
                class="list-row"
              >
                <span class="badge-blue">P{{ i + 1 }}</span>
                <el-input v-model="form.coreIdentity.principles[i]" size="small" />
                <el-button size="small" type="danger" text @click="removePrinciple(i)">✕</el-button>
              </div>
              <div class="list-footer">
                <el-button size="small" @click="addPrinciple">＋ 添加原则</el-button>
                <el-text size="small" type="info">序号越小优先级越高</el-text>
              </div>
            </div>
          </el-form-item>

          <el-form-item label="绝对底线">
            <div class="list-editor">
              <el-alert
                v-if="form.coreIdentity.redlines.length"
                type="error"
                :closable="false"
                style="margin-bottom:8px;padding:6px 12px"
              >
                底线优先级凌驾于所有其他规则之上，任何情况下不得违反
              </el-alert>
              <div
                v-for="(_, i) in form.coreIdentity.redlines"
                :key="i"
                class="list-row"
              >
                <el-input v-model="form.coreIdentity.redlines[i]" size="small" />
                <el-button size="small" type="danger" text @click="removeRedline(i)">✕</el-button>
              </div>
              <el-button size="small" type="danger" plain @click="addRedline">＋ 添加底线</el-button>
            </div>
          </el-form-item>

          <el-form-item label="语言风格">
            <el-input
              v-model="form.coreIdentity.languageStyle"
              type="textarea"
              :rows="2"
              placeholder="如：亲切自然，句子简短，偶尔用表情符号"
            />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ── Tab 3：用户画像 ── -->
      <el-tab-pane label="用户画像" name="profile">
        <el-form :model="form.userProfile" label-width="90px">
          <el-form-item label="昵称">
            <el-input v-model="form.userProfile.nickname" />
          </el-form-item>
          <el-form-item label="关系设定">
            <el-input v-model="form.userProfile.relationship" placeholder="如：知心朋友、导师" />
          </el-form-item>
          <el-form-item label="背景">
            <el-input
              v-model="form.userProfile.background"
              type="textarea"
              :rows="2"
              placeholder="用户职业、兴趣爱好等"
            />
          </el-form-item>
          <el-form-item label="沟通风格">
            <div style="display:flex;gap:8px">
              <el-select v-model="form.userProfile.preferences.tone" style="width:140px">
                <el-option label="随意 casual" value="casual" />
                <el-option label="正式 formal" value="formal" />
                <el-option label="专业 professional" value="professional" />
              </el-select>
              <el-select v-model="form.userProfile.preferences.detail" style="width:140px">
                <el-option label="简短 brief" value="brief" />
                <el-option label="适中 moderate" value="moderate" />
                <el-option label="详细 detailed" value="detailed" />
              </el-select>
            </div>
          </el-form-item>
          <el-form-item label="已透露信息">
            <TagInput v-model="form.userProfile.disclosedInfo" placeholder="如：住在上海…" />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ── Tab 4：角色记忆（只读展示 + 检索规则） ── -->
      <el-tab-pane label="角色记忆" name="memory">
        <el-alert type="warning" :closable="false" style="margin-bottom:16px">
          短期记忆存于 Java 后端进程内（⚠️ 服务重启后清空，保留最近 100 条 / 24 小时）；
          长期记忆持久化为按用户的 JSON 文件。
        </el-alert>

        <el-divider>承诺列表</el-divider>
        <el-empty
          v-if="!form.roleMemory.commitments?.length"
          description="暂无承诺"
          :image-size="60"
        />
        <el-timeline v-else>
          <el-timeline-item
            v-for="(c, i) in form.roleMemory.commitments"
            :key="i"
            :timestamp="c.timestamp?.slice(0, 10)"
            :type="c.status === 'active' ? 'primary' : 'info'"
          >
            {{ c.content }}
            <el-tag
              size="small"
              :type="c.status === 'active' ? 'success' : 'info'"
              style="margin-left:8px"
            >{{ c.status }}</el-tag>
          </el-timeline-item>
        </el-timeline>

        <el-divider>检索规则</el-divider>
        <el-form :model="form.roleMemory.retrievalRule" label-width="120px">
          <el-form-item label="相似度阈值">
            <el-slider
              v-model="form.roleMemory.retrievalRule.similarityThreshold"
              :min="0" :max="1" :step="0.05"
              show-input
            />
          </el-form-item>
          <el-form-item label="最大召回条数">
            <el-input-number
              v-model="form.roleMemory.retrievalRule.topK"
              :min="1" :max="20"
            />
          </el-form-item>
          <el-form-item label="时间近似权重">
            <el-slider
              v-model="form.roleMemory.retrievalRule.recencyWeight"
              :min="0" :max="1" :step="0.05"
              show-input
            />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ── Tab 5：知识与日记 ── -->
      <el-tab-pane label="知识与日记" name="knowledge">
        <el-divider>近期日记</el-divider>
        <el-empty
          v-if="!form.knowledgeJournal.recentJournalEntries?.length"
          description="暂无日记"
          :image-size="60"
        />
        <el-card
          v-for="(entry, i) in form.knowledgeJournal.recentJournalEntries"
          :key="i"
          class="journal-card"
        >
          <template #header>
            <el-text type="info" size="small">📔 {{ entry.date }}</el-text>
          </template>
          <p style="margin:0;white-space:pre-wrap">{{ entry.content }}</p>
        </el-card>

        <el-divider>知识库来源</el-divider>
        <div v-if="form.knowledgeJournal.knowledgeSources?.length" style="margin-bottom:12px">
          <el-tag
            v-for="src in form.knowledgeJournal.knowledgeSources"
            :key="src"
            style="margin:4px"
            closable
            @close="removeKnowledgeSource(src)"
          >{{ src }}</el-tag>
        </div>
        <el-text v-else type="info" size="small">暂无知识库来源</el-text>

        <div style="margin-top:16px">
          <el-upload
            :auto-upload="false"
            :on-change="onKnowledgeFileChange"
            :show-file-list="false"
            accept=".txt,.md,.pdf"
          >
            <el-button size="small" type="primary" plain>上传知识文档</el-button>
          </el-upload>
          <el-text size="small" type="info" style="display:block;margin-top:4px">
            支持 .txt / .md / .pdf，实际向量化由后端处理
          </el-text>
        </div>
      </el-tab-pane>

      <!-- ── Tab 6：提示预览 ── -->
      <el-tab-pane label="提示预览" name="preview">
        <div class="preview-hint">
          <i class="fas fa-eye" /> 以下是根据当前表单编译的系统提示预览（实时更新）
        </div>
        <div class="md-preview" v-html="previewHtml" />
      </el-tab-pane>

    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, toRaw } from 'vue'
import {
  ElTabs, ElTabPane,
  ElSelect, ElOption,
  ElForm, ElFormItem,
  ElInput,
  ElButton,
  ElAvatar,
  ElSwitch,
  ElTooltip,
  ElTag,
  ElAlert,
  ElDivider,
  ElEmpty,
  ElTimeline, ElTimelineItem,
  ElSlider,
  ElInputNumber,
  ElCard,
  ElText,
  ElUpload,
  ElMessage,
} from 'element-plus'
import { useConfirmDialogStore } from '@/stores/confirmDialog'
import { saveRole, loadRole, listRoles, deleteRole, newRoleConfig } from '@/services/roleStorage'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import TagInput from '@/components/common/TagInput.vue'

// ── 后端 API（可选） ──────────────────────────────────────────────────────
async function apiRequest(method, path, body) {
  const token = localStorage.getItem('agent_token')
  const res = await fetch(`/api/roles${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`API ${method} ${path} 返回 ${res.status}`)
  return res.json()
}

// camelCase 前端表单 → snake_case 后端 RoleConfig
function formToApiPayload(f) {
  const p = JSON.parse(JSON.stringify(f))
  return {
    role_id: p.roleId,
    role_card: {
      name:       p.roleCard?.name ?? '',
      avatar_url: p.roleCard?.avatarUrl ?? '',
      signature:  p.roleCard?.signature ?? '',
      tags:       p.roleCard?.tags ?? [],
    },
    core_identity: {
      personality:    p.coreIdentity?.personality ?? [],
      principles:     p.coreIdentity?.principles ?? [],
      redlines:       p.coreIdentity?.redlines ?? [],
      language_style: p.coreIdentity?.languageStyle ?? '',
    },
    user_profile: {
      nickname:      p.userProfile?.nickname ?? '',
      background:    p.userProfile?.background ?? '',
      relationship:  p.userProfile?.relationship ?? '',
      preferences:   p.userProfile?.preferences ?? { tone: 'casual', detail: 'moderate' },
      disclosed_info: p.userProfile?.disclosedInfo ?? [],
    },
    role_memory: {
      commitments: p.roleMemory?.commitments ?? [],
      retrieval_rule: {
        similarity_threshold: p.roleMemory?.retrievalRule?.similarityThreshold ?? 0.7,
        top_k:                p.roleMemory?.retrievalRule?.topK ?? 5,
        recency_weight:       p.roleMemory?.retrievalRule?.recencyWeight ?? 0.3,
      },
      short_term_size: p.roleMemory?.shortTermSize ?? 20,
    },
    knowledge_journal: {
      recent_journal_entries: p.knowledgeJournal?.recentJournalEntries ?? [],
      retrieval_strategy:     p.knowledgeJournal?.retrievalStrategy ?? 'hybrid',
      knowledge_sources:      p.knowledgeJournal?.knowledgeSources ?? [],
    },
  }
}

// ── 响应式状态 ────────────────────────────────────────────────────────────
const confirmDialog = useConfirmDialogStore()
const syncEnabled = ref(false)
const saving = ref(false)
const activating = ref(false)
const activeTab = ref('card')
const currentRoleId = ref('')
const activeRoleId = ref('')   // 后端当前激活的 role_id
const roleList = ref([])
const avatarInputEl = ref(null)
const form = reactive(newRoleConfig())

// 当前表单对应的角色是否为激活状态
const isActiveRole = computed(() => !!currentRoleId.value && currentRoleId.value === activeRoleId.value)

// ── 生命周期 ─────────────────────────────────────────────────────────────
onMounted(async () => {
  roleList.value = await listRoles()
  // 拉取后端当前激活角色
  try {
    const data = await apiRequest('GET', '/activate')
    if (data.role_id) activeRoleId.value = data.role_id
  } catch { /* 后端未接入时静默失败 */ }
})

// ── 角色切换 ─────────────────────────────────────────────────────────────
async function onRoleSelect(roleId) {
  if (roleId === '__new__') {
    const blank = newRoleConfig()
    Object.assign(form, blank)
    currentRoleId.value = blank.roleId
    return
  }
  const role = await loadRole(roleId)
  if (role) Object.assign(form, role)
}

// ── 保存 ─────────────────────────────────────────────────────────────────
async function handleSave() {
  if (!form.roleCard.name?.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  saving.value = true
  try {
    await saveRole(JSON.parse(JSON.stringify(toRaw(form))))
    if (syncEnabled.value) {
      const payload = formToApiPayload(form)
      const exists = roleList.value.some(r => r.roleId === form.roleId)
      if (exists) {
        await apiRequest('PUT', `/${form.roleId}`, payload)
      } else {
        await apiRequest('POST', '', payload)
      }
    }
    roleList.value = await listRoles()
    currentRoleId.value = form.roleId
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(`保存失败: ${e.message}`)
  } finally {
    saving.value = false
  }
}

// ── 删除 ─────────────────────────────────────────────────────────────────
function handleDelete() {
  confirmDialog.open({
    title: '删除角色',
    message: `确认删除角色「${form.roleCard.name}」？此操作不可撤销。`,
    confirmText: '删除',
    confirmType: 'danger',
    onConfirm: async () => {
      await deleteRole(form.roleId)
      if (syncEnabled.value) {
        await apiRequest('DELETE', `/${form.roleId}`).catch(() => {})
      }
      const blank = newRoleConfig()
      Object.assign(form, blank)
      currentRoleId.value = ''
      roleList.value = await listRoles()
      ElMessage.success('已删除')
    },
  })
}

// ── 激活 / 停用 ───────────────────────────────────────────────────────────
async function handleActivate() {
  if (!currentRoleId.value) return
  activating.value = true
  try {
    // 确保角色已同步到后端（使用 snake_case payload）
    await apiRequest('PUT', `/${form.roleId}`, formToApiPayload(form))
    await apiRequest('POST', '/activate', { role_id: form.roleId })
    activeRoleId.value = form.roleId
    ElMessage.success(`已激活角色「${form.roleCard.name}」，AI 将使用此角色配置`)
  } catch (e) {
    ElMessage.error(`激活失败: ${e.message}`)
  } finally {
    activating.value = false
  }
}

async function handleDeactivate() {
  try {
    await apiRequest('DELETE', '/activate')
    activeRoleId.value = ''
    ElMessage.success('已停用角色，AI 恢复默认模板')
  } catch (e) {
    ElMessage.error(`停用失败: ${e.message}`)
  }
}

// ── 原则 & 底线增删 ──────────────────────────────────────────────────────
function addPrinciple() { form.coreIdentity.principles.push('') }
function removePrinciple(i) { form.coreIdentity.principles.splice(i, 1) }
function addRedline() { form.coreIdentity.redlines.push('') }
function removeRedline(i) { form.coreIdentity.redlines.splice(i, 1) }

// ── 头像上传 ─────────────────────────────────────────────────────────────
function triggerAvatarUpload() { avatarInputEl.value?.click() }

function onAvatarChange(e) {
  const file = e.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = (ev) => { form.roleCard.avatarUrl = ev.target.result }
  reader.readAsDataURL(file)
}

// ── 知识库管理 ───────────────────────────────────────────────────────────
function removeKnowledgeSource(src) {
  const idx = form.knowledgeJournal.knowledgeSources.indexOf(src)
  if (idx !== -1) form.knowledgeJournal.knowledgeSources.splice(idx, 1)
}

function onKnowledgeFileChange(file) {
  ElMessage.info(`"${file.name}" 已加入知识库来源，保存后生效`)
  if (!form.knowledgeJournal.knowledgeSources.includes(file.name)) {
    form.knowledgeJournal.knowledgeSources.push(file.name)
  }
}

// ── 提示预览 ─────────────────────────────────────────────────────────────
const _MD_ALLOWED = {
  ALLOWED_TAGS: ['p','br','strong','em','code','pre','blockquote','ul','ol','li',
                 'h1','h2','h3','h4','h5','h6','hr','span','table','thead','tbody','tr','th','td'],
  ALLOWED_ATTR: ['class'],
}

const previewHtml = computed(() => {
  const c  = form.roleCard
  const id = form.coreIdentity
  const up = form.userProfile

  const lines = []
  lines.push(`# ${c.name || '（未命名角色）'}`)
  if (c.signature) lines.push(`\n> ${c.signature}`)
  if (c.tags?.length) lines.push(`\n**标签**: ${c.tags.join('、')}`)

  lines.push(`\n---\n\n## 核心身份`)
  if (id.personality?.length) lines.push(`\n**性格**: ${id.personality.join('、')}`)
  if (id.languageStyle) lines.push(`\n**语言风格**: ${id.languageStyle}`)
  if (id.principles?.length) {
    lines.push(`\n**行为原则**:\n`)
    id.principles.forEach((p, i) => lines.push(`${i + 1}. ${p}`))
  }
  if (id.redlines?.length) {
    lines.push(`\n**绝对底线** ⚠️:\n`)
    id.redlines.forEach(r => lines.push(`- ${r}`))
  }

  lines.push(`\n---\n\n## 用户画像`)
  lines.push(`- **昵称**: ${up.nickname || '（未设置）'}`)
  lines.push(`- **关系**: ${up.relationship || '朋友'}`)
  if (up.background) lines.push(`- **背景**: ${up.background}`)

  const raw = lines.join('\n')
  return DOMPurify.sanitize(marked.parse(raw), _MD_ALLOWED)
})
</script>

<style scoped>
.role-editor {
  padding: var(--space-4);
  max-width: var(--content-max-width);
  margin: 0 auto;
  /* 让 El Plus 表单背景融入页面灰色背景，避免大面积白块割裂感 */
}

/* ── El Plus 主题适配：融入应用整体风格 ─────────────────────── */
:deep(.el-tabs__header) {
  margin-bottom: 20px;
}
:deep(.el-tabs__item) {
  color: var(--color-text-secondary);
  font-size: 0.9rem;
}
:deep(.el-tabs__item.is-active) {
  color: var(--color-primary);
  font-weight: 600;
}
:deep(.el-tabs__active-bar) {
  background-color: var(--color-primary);
}
:deep(.el-form-item__label) {
  color: var(--color-text-secondary);
  font-size: 0.88rem;
}
:deep(.el-input__wrapper),
:deep(.el-textarea__inner) {
  border-radius: var(--radius-sm);
  box-shadow: 0 0 0 1px var(--color-border) inset;
}
:deep(.el-input__wrapper:hover),
:deep(.el-textarea__inner:hover) {
  box-shadow: 0 0 0 1px var(--color-primary) inset;
}
:deep(.el-input__wrapper.is-focus),
:deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--color-primary) inset;
}
:deep(.el-button--primary) {
  background: var(--color-primary);
  border-color: var(--color-primary);
}
:deep(.el-button--primary:hover) {
  background: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
}
:deep(.el-button--primary.is-plain) {
  background: transparent;
  color: var(--color-primary);
  border-color: var(--color-primary);
}
:deep(.el-button--primary.is-plain:hover) {
  background: var(--color-surface-raised);
  color: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
}
:deep(.el-button--danger) {
  background: var(--color-danger);
  border-color: var(--color-danger);
}
:deep(.el-button--danger:hover) {
  background: #dc2626;
  border-color: #dc2626;
}
:deep(.el-select .el-input__wrapper) {
  border-radius: var(--radius-sm);
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-4);
}
.header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.card-guide-alert { margin-bottom: var(--space-1); }
.card-guide-body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 0.82rem;
  color: var(--color-text-secondary);
  margin-top: var(--space-1);
}

.avatar-row {
  display: flex;
  align-items: center;
}

.list-editor {
  width: 100%;
}
.list-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: 6px;
}
.list-row .el-input {
  flex: 1;
}
.list-footer {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-1);
}

.field-hint {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin-top: var(--space-1);
  display: flex;
  align-items: center;
  gap: var(--space-1);
}
.field-hint kbd {
  background: var(--color-border);
  border: 1px solid #d0d3da;
  border-radius: 3px;
  padding: 0 4px;
  font-size: 0.7rem;
  color: var(--color-text-secondary);
}

.badge-blue {
  flex-shrink: 0;
  font-size: 11px;
  color: #fff;
  background: #409eff;
  border-radius: 4px;
  padding: 2px 6px;
}

.journal-card {
  margin-bottom: 10px;
}

:deep(.tag-input-wrap) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}

.preview-hint {
  font-size: 0.8rem;
  color: var(--color-text-muted);
  margin-bottom: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.md-preview {
  background: var(--color-bg);
  border: 1px solid #eef0f4;
  border-radius: var(--radius-md);
  padding: 20px 24px;
  font-size: 0.92rem;
  line-height: 1.7;
  color: var(--color-text);
  min-height: 200px;
}
.md-preview :deep(h1) { font-size: 1.3rem; font-weight: 600; margin: 0 0 10px; color: var(--color-text); }
.md-preview :deep(h2) { font-size: 1.05rem; font-weight: 600; margin: var(--space-4) 0 var(--space-2); color: var(--color-text); border-bottom: 1px solid var(--color-border); padding-bottom: var(--space-1); }
.md-preview :deep(h3) { font-size: 0.95rem; font-weight: 600; margin: 12px 0 6px; }
.md-preview :deep(blockquote) { border-left: 3px solid var(--color-primary); padding: 4px 12px; margin: 8px 0; color: var(--color-text-secondary); background: #f8f8ff; border-radius: 0 6px 6px 0; }
.md-preview :deep(ul), .md-preview :deep(ol) { padding-left: 20px; margin: 6px 0; }
.md-preview :deep(li) { margin-bottom: 3px; }
.md-preview :deep(hr) { border: none; border-top: 1px solid var(--color-border); margin: 14px 0; }
.md-preview :deep(strong) { color: #444; }
.md-preview :deep(p) { margin: 0 0 8px; }
</style>
