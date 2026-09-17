import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'

// ── TD-06：视图挂载冒烟 ─────────────────────────────────────────
// 目标不是断言业务逻辑，而是保证 18 个视图在真实依赖（pinia/router/Element Plus）
// 下能挂载并渲染，不再出现「改了共享组件/工具函数，某个页面白屏且无人发现」。
// 所有后端调用统一打桩：返回 Promise.resolve({})，视图自身的兜底逻辑负责容错。
vi.mock('@/services/api', async (importOriginal) => {
  const actual = await importOriginal()
  const mocked = {}
  for (const key of Object.keys(actual)) {
    // 函数统一打桩为返回空对象的异步函数；常量（BASE 等）保留真实值。
    // 必须显式列出导出名，否则 Vitest 的 export 校验会对每个命名导入报错。
    mocked[key] = typeof actual[key] === 'function' ? vi.fn(async () => ({})) : actual[key]
  }
  return mocked
})

const VIEWS = {
  LoginView: () => import('@/views/LoginView.vue'),
  ChatView: () => import('@/views/ChatView.vue'),
  ImageView: () => import('@/views/ImageView.vue'),
  KnowledgeView: () => import('@/views/KnowledgeView.vue'),
  LogView: () => import('@/views/LogView.vue'),
  MCPView: () => import('@/views/MCPView.vue'),
  MemoryView: () => import('@/views/MemoryView.vue'),
  ModelView: () => import('@/views/ModelView.vue'),
  ProjectView: () => import('@/views/ProjectView.vue'),
  RoleEditorView: () => import('@/views/RoleEditorView.vue'),
  SkillView: () => import('@/views/SkillView.vue'),
  StatsView: () => import('@/views/StatsView.vue'),
  SystemView: () => import('@/views/SystemView.vue'),
  TasksView: () => import('@/views/TasksView.vue'),
  ToolsView: () => import('@/views/ToolsView.vue'),
  TraceView: () => import('@/views/TraceView.vue'),
  LearningSubmitView: () => import('@/views/learning/SubmitView.vue'),
  LearningReviewView: () => import('@/views/learning/ReviewView.vue'),
}

async function mountView(loader) {
  const component = (await loader()).default
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  router.push('/chat')
  await router.isReady()

  const errors = []
  const errorSpy = vi.spyOn(console, 'error').mockImplementation((...args) => {
    errors.push(args.map(String).join(' '))
  })
  const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

  let wrapper
  try {
    wrapper = mount(component, {
      global: {
        plugins: [createPinia(), router, ElementPlus],
        stubs: { teleport: true, transition: false },
      },
    })
    await flushPromises()
  } finally {
    warnSpy.mockRestore()
    errorSpy.mockRestore()
  }

  const html = wrapper.html()
  wrapper.unmount()
  return { html, errors }
}

describe('视图挂载冒烟（TD-06）', () => {
  for (const [name, loader] of Object.entries(VIEWS)) {
    it(`${name} 能挂载并渲染`, async () => {
      const { html, errors } = await mountView(loader)

      expect(html.length, `${name} 渲染为空`).toBeGreaterThan(0)
      const fatal = errors.filter(e => /\[Vue warn\]: Unhandled error|Cannot read|is not a function|is not defined/.test(e))
      expect(fatal, `${name} 挂载期间产生致命错误:\n${fatal.join('\n')}`).toEqual([])
    })
  }
})
