<template>
  <transition name="slide-up">
    <div v-if="show" class="install-banner">
      <div class="install-info">
        <i class="fas fa-mobile-alt install-icon" />
        <div>
          <div class="install-title">安装到桌面</div>
          <div class="install-sub">离线可用 · 类原生体验</div>
        </div>
      </div>
      <div class="install-actions">
        <button class="install-btn" @click="install">安装</button>
        <button class="dismiss-btn" @click="dismiss"><i class="fas fa-times" /></button>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const show        = ref(false)
let   deferredEvt = null

const onPrompt = (e) => {
  e.preventDefault()
  deferredEvt = e
  // only show if not dismissed in this session
  if (!sessionStorage.getItem('pwa-dismissed')) {
    show.value = true
  }
}

const install = async () => {
  if (!deferredEvt) return
  deferredEvt.prompt()
  const { outcome } = await deferredEvt.userChoice
  if (outcome === 'accepted') show.value = false
  deferredEvt = null
}

const dismiss = () => {
  show.value = false
  sessionStorage.setItem('pwa-dismissed', '1')
}

onMounted(() => window.addEventListener('beforeinstallprompt', onPrompt))
onUnmounted(() => window.removeEventListener('beforeinstallprompt', onPrompt))
</script>

<style scoped>
.install-banner {
  position: fixed;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #1a1a2e;
  color: white;
  padding: 14px 20px;
  border-radius: 14px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.25);
  z-index: 9999;
  min-width: 320px;
  max-width: 480px;
}
.install-info { display: flex; align-items: center; gap: 12px; }
.install-icon { font-size: 1.4rem; color: #667eea; }
.install-title { font-size: 0.9rem; font-weight: 600; }
.install-sub   { font-size: 0.75rem; color: #aaa; margin-top: 2px; }
.install-actions { display: flex; align-items: center; gap: 8px; }
.install-btn {
  padding: 7px 16px; border-radius: 8px;
  background: #667eea; color: white; border: none;
  font-size: 0.85rem; font-weight: 500; cursor: pointer;
  transition: background 0.2s;
}
.install-btn:hover { background: #5a6fd6; }
.dismiss-btn {
  background: none; border: none; color: #888;
  cursor: pointer; font-size: 0.9rem; padding: 6px;
  border-radius: 6px; transition: color 0.2s;
}
.dismiss-btn:hover { color: white; }

.slide-up-enter-active, .slide-up-leave-active { transition: all 0.3s ease; }
.slide-up-enter-from, .slide-up-leave-to { transform: translateX(-50%) translateY(80px); opacity: 0; }
</style>
