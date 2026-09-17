import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import ChatView from '@/views/ChatView.vue'
import { useWebSocketStore } from '@/stores/websocket'

// TD-15：聊天页远程模型入口
// 覆盖两件事：① 没配服务商时有「添加远程模型」引导；② 配了服务商时该模型带云图标。
vi.mock('@/services/api', async (importOriginal) => {
  const actual = await importOriginal()
  const mocked = {}
  for (const key of Object.keys(actual)) {
    mocked[key] = typeof actual[key] === 'function' ? vi.fn(async () => ({})) : actual[key]
  }
  return mocked
})

async function mountChat() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  router.push('/chat')
  await router.isReady()
  vi.spyOn(console, 'warn').mockImplementation(() => {})
  const wrapper = mount(ChatView, {
    global: {
      plugins: [pinia, router, ElementPlus],
      stubs: { teleport: true, transition: false },
    },
  })
  await flushPromises()
  return { wrapper, store: useWebSocketStore(pinia) }
}

describe('聊天页远程模型入口（TD-15）', () => {
  beforeEach(() => { vi.spyOn(console, 'error').mockImplementation(() => {}) })

  it('未配置远程模型时给出添加引导', async () => {
    const { wrapper, store } = await mountChat()
    store.availableModels = ['qwen2.5:7b']
    store.cloudProvider = ''
    store.cloudModelName = ''
    await flushPromises()

    await wrapper.find('.config-model-btn').trigger('click')
    await flushPromises()

    const dropdown = wrapper.find('.config-model-dropdown')
    expect(dropdown.exists()).toBe(true)
    expect(dropdown.text()).toContain('添加远程模型')
    expect(dropdown.find('.config-dropdown-hint').text()).toContain('尚未配置远程模型')
    wrapper.unmount()
  })

  it('已配置远程模型时条目带云图标且入口变管理', async () => {
    const { wrapper, store } = await mountChat()
    store.availableModels = ['qwen2.5:7b', 'qwen-max']
    store.cloudProvider = 'dashscope'
    store.cloudModelName = 'qwen-max'
    await flushPromises()

    await wrapper.find('.config-model-btn').trigger('click')
    await flushPromises()

    const dropdown = wrapper.find('.config-model-dropdown')
    expect(dropdown.text()).toContain('管理远程模型')
    expect(dropdown.find('.config-dropdown-hint').exists()).toBe(false)

    const items = dropdown.findAll('.config-dropdown-item')
    const cloudItem = items.find(i => i.text().includes('qwen-max'))
    const localItem = items.find(i => i.text().includes('qwen2.5:7b'))
    expect(cloudItem.find('i').classes()).toContain('fa-cloud')
    expect(localItem.find('i').classes()).toContain('fa-cube')
    wrapper.unmount()
  })
})
