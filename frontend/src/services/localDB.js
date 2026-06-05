/**
 * IndexedDB wrapper for local chat session persistence.
 * Stores sessions so conversations survive page refreshes and work offline.
 */

const DB_NAME        = 'intelligent-agent'
const DB_VERSION     = 2
const STORE          = 'sessions'
const PROJECTS_STORE = 'projects'

let _db = null

async function openDB() {
  if (_db) return _db
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = (e) => {
      const db = e.target.result
      // v1: sessions store
      if (!db.objectStoreNames.contains(STORE)) {
        const store = db.createObjectStore(STORE, { keyPath: 'id' })
        store.createIndex('updated_at', 'updated_at', { unique: false })
      }
      // v2: projects store
      if (e.oldVersion < 2 && !db.objectStoreNames.contains(PROJECTS_STORE)) {
        const ps = db.createObjectStore(PROJECTS_STORE, { keyPath: 'id' })
        ps.createIndex('updated_at', 'updated_at', { unique: false })
      }
    }
    req.onsuccess  = (e) => { _db = e.target.result; resolve(_db) }
    req.onerror    = (e) => reject(e.target.error)
  })
}

function tx(mode = 'readonly') {
  return _db.transaction(STORE, mode).objectStore(STORE)
}

function ptx(mode = 'readonly') {
  return _db.transaction(PROJECTS_STORE, mode).objectStore(PROJECTS_STORE)
}

function wrap(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror   = () => reject(req.error)
  })
}

// ── Public API ────────────────────────────────────────────────────────────────

export async function saveSession(session) {
  await openDB()
  return wrap(tx('readwrite').put(session))
}

export async function getSession(id) {
  await openDB()
  return wrap(tx().get(id))
}

export async function listSessions(limit = 50) {
  await openDB()
  return new Promise((resolve, reject) => {
    const results = []
    const index   = tx().index('updated_at')
    const req     = index.openCursor(null, 'prev')   // newest first
    req.onsuccess = (e) => {
      const cursor = e.target.result
      if (!cursor || results.length >= limit) {
        resolve(results)
        return
      }
      results.push(cursor.value)
      cursor.continue()
    }
    req.onerror = () => reject(req.error)
  })
}

export async function deleteSession(id) {
  await openDB()
  return wrap(tx('readwrite').delete(id))
}

export async function clearAllSessions() {
  await openDB()
  return wrap(tx('readwrite').clear())
}

export async function exportAllSessions() {
  await openDB()
  return listSessions(10000)
}

export async function importSessions(sessions) {
  await openDB()
  // await 每条写操作，确保错误不被静默吞掉，返回值反映实际成功数
  let imported = 0
  for (const s of sessions) {
    try {
      await wrap(tx('readwrite').put(s))
      imported++
    } catch (e) {
      console.warn('[localDB] importSessions: failed to import session', s?.id, e)
    }
  }
  return imported
}

// ── Projects API ──────────────────────────────────────────────────────────────

export function makeProject(title) {
  const now = new Date().toISOString()
  return {
    id:              `proj_${crypto.randomUUID()}`,
    title,
    created_at:      now,
    updated_at:      now,
    session_ids:     [],
    context_version: 0,
    context_summary: '',
    spec: {
      content:           '',
      version:           1,
      last_updated:      '',
      last_reviewed_turn: 0,
    },
    task_tree: {
      root_tasks:    [],
      auto_decompose: true,
      last_updated:  '',
    },
    synced: false,
  }
}

export async function saveProject(project) {
  await openDB()
  return wrap(ptx('readwrite').put(project))
}

export async function getProject(id) {
  await openDB()
  return wrap(ptx().get(id))
}

export async function listProjects(limit = 50) {
  await openDB()
  return new Promise((resolve, reject) => {
    const results = []
    const index   = ptx().index('updated_at')
    const req     = index.openCursor(null, 'prev')
    req.onsuccess = (e) => {
      const cursor = e.target.result
      if (!cursor || results.length >= limit) { resolve(results); return }
      results.push(cursor.value)
      cursor.continue()
    }
    req.onerror = () => reject(req.error)
  })
}

export async function deleteProject(id) {
  await openDB()
  return wrap(ptx('readwrite').delete(id))
}

export async function clearAllProjects() {
  await openDB()
  return wrap(ptx('readwrite').clear())
}
