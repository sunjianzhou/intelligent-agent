/**
 * roleStorage.js — IndexedDB 工具函数，用于本地持久化角色配置。
 *
 * DB 结构：
 *   数据库名：intelligent-agent-roles
 *   版本：1
 *   Store：roles（keyPath: roleId）
 *
 * 用法：
 *   import { saveRole, loadRole, listRoles, deleteRole } from '@/services/roleStorage'
 */

const DB_NAME = 'intelligent-agent-roles'
const DB_VERSION = 1
const STORE_NAME = 'roles'

/** 打开/初始化 IndexedDB */
function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)

    req.onupgradeneeded = (e) => {
      const db = e.target.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'roleId' })
      }
    }

    req.onsuccess = (e) => resolve(e.target.result)
    req.onerror = (e) => reject(e.target.error)
  })
}

/** 保存或更新角色配置 */
export async function saveRole(roleConfig) {
  const db = await openDB()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite')
    const store = tx.objectStore(STORE_NAME)
    // 再做一次深克隆，确保 Vue Proxy 对象不进入 IndexedDB 结构化克隆
    const plain = JSON.parse(JSON.stringify(roleConfig))
    plain.updatedAt = new Date().toISOString()
    const req = store.put(plain)
    req.onsuccess = () => resolve(plain)
    req.onerror = (e) => reject(e.target.error)
  })
}

/** 加载单个角色配置，不存在返回 null */
export async function loadRole(roleId) {
  const db = await openDB()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readonly')
    const req = tx.objectStore(STORE_NAME).get(roleId)
    req.onsuccess = (e) => resolve(e.target.result ?? null)
    req.onerror = (e) => reject(e.target.error)
  })
}

/** 获取所有角色配置列表 */
export async function listRoles() {
  const db = await openDB()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readonly')
    const req = tx.objectStore(STORE_NAME).getAll()
    req.onsuccess = (e) => resolve(e.target.result)
    req.onerror = (e) => reject(e.target.error)
  })
}

/** 删除角色配置 */
export async function deleteRole(roleId) {
  const db = await openDB()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readwrite')
    const req = tx.objectStore(STORE_NAME).delete(roleId)
    req.onsuccess = () => resolve(true)
    req.onerror = (e) => reject(e.target.error)
  })
}

/** 构造一个空角色配置骨架 */
export function newRoleConfig(overrides = {}) {
  return {
    roleId: `role_${Date.now()}`,
    roleCard: { name: '', avatarUrl: '', signature: '', tags: [] },
    coreIdentity: {
      personality: [],
      principles: [],
      redlines: [],
      languageStyle: '',
    },
    userProfile: {
      nickname: '用户',
      background: '',
      preferences: { tone: 'casual', detail: 'moderate' },
      relationship: '朋友',
      disclosedInfo: [],
    },
    roleMemory: {
      commitments: [],
      retrievalRule: { similarityThreshold: 0.7, topK: 5, recencyWeight: 0.3 },
      shortTermSize: 20,
    },
    knowledgeJournal: {
      recentJournalEntries: [],
      retrievalStrategy: 'semantic_similarity',
      knowledgeSources: [],
    },
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...overrides,
  }
}
