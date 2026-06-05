import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { saveSession, getSession, listSessions, deleteSession, clearAllSessions } from '@/services/localDB'

function newId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

function titleFrom(content) {
  return content.slice(0, 28) + (content.length > 28 ? '…' : '')
}

export const useLocalSessionStore = defineStore('localSession', () => {
  const sessions      = ref([])       // summary list for sidebar
  const activeId      = ref(null)
  const messages      = ref([])       // messages of the active session
  const model         = ref('')
  const persona       = ref('default')

  const activeSession = computed(() => sessions.value.find(s => s.id === activeId.value) || null)

  // ── Init / Load ───────────────────────────────────────────────────────────

  async function loadSessions() {
    try {
      const all = await listSessions(100)
      // 过滤掉没有任何用户消息的空会话，并从 IDB 中删除
      const empties = all.filter(s => !s.messages?.some(m => m.role === 'user'))
      for (const s of empties) {
        await deleteSession(s.id).catch(() => {})
      }
      sessions.value = all.filter(s => s.messages?.some(m => m.role === 'user'))
    } catch (e) {
      console.warn('[localDB] loadSessions failed', e)
    }
  }

  async function resumeSession(id) {
    try {
      const s = await getSession(id)
      if (!s) return false
      activeId.value = s.id
      messages.value = s.messages || []
      model.value    = s.model    || ''
      persona.value  = s.persona  || 'default'
      return true
    } catch (e) {
      console.warn('[localDB] resumeSession failed', e)
      return false
    }
  }

  async function startNewSession() {
    // 如果当前会话已无内容，直接复用，不新建
    const hasContent = messages.value.some(m => m.role === 'user')
    if (!hasContent && activeId.value) {
      messages.value = []
      return
    }
    activeId.value = newId()
    messages.value = []
    // 不立即持久化：等到第一条消息发送后再写 IDB，避免空会话堆积
    await loadSessions()
  }

  // ── Append messages ───────────────────────────────────────────────────────

  async function addMessage(msg) {
    messages.value.push(msg)
    await _persist()
    const idx = sessions.value.findIndex(s => s.id === activeId.value)
    if (idx >= 0) {
      sessions.value[idx].updated_at = new Date().toISOString()
      sessions.value[idx].preview = messages.value
        .filter(m => m.role === 'user').at(0)?.content?.slice(0, 40) || ''
    } else if (messages.value.some(m => m.role === 'user')) {
      // 首次发送用户消息时，session 尚未出现在 sidebar，reload 让它出现
      await loadSessions()
    }
  }

  function setModelPersona(m, p) {
    model.value   = m || model.value
    persona.value = p || persona.value
  }

  // ── Delete ────────────────────────────────────────────────────────────────

  async function removeSession(id) {
    await deleteSession(id)
    sessions.value = sessions.value.filter(s => s.id !== id)
    if (activeId.value === id) {
      await startNewSession()
    }
  }

  async function clearAll() {
    await clearAllSessions()
    sessions.value = []
    await startNewSession()
  }

  // ── Internal ──────────────────────────────────────────────────────────────

  async function _persist() {
    if (!activeId.value) return
    // 跳过流式进行中的持久化：每个 token 触发一次全量深克隆（O(N)），等流结束再写
    if (messages.value.some(m => m.isStreaming)) return
    const firstUser = messages.value.find(m => m.role === 'user')
    const title     = firstUser ? titleFrom(firstUser.content) : '新对话'
    const now       = new Date().toISOString()
    const session   = {
      id:         activeId.value,
      title,
      model:      model.value,
      persona:    persona.value,
      messages:   JSON.parse(JSON.stringify(messages.value)),
      created_at: sessions.value.find(s => s.id === activeId.value)?.created_at || now,
      updated_at: now,
      preview:    messages.value.filter(m => m.role === 'user').at(0)?.content?.slice(0, 40) || '',
    }
    try {
      await saveSession(session)
    } catch (e) {
      console.warn('[localDB] persist failed', e)
    }
  }

  return {
    sessions, activeId, messages, model, persona, activeSession,
    loadSessions, resumeSession, startNewSession,
    addMessage, setModelPersona, removeSession, clearAll,
  }
})
