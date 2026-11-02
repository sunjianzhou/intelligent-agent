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
  gap: var(--space-4);
  background: var(--surface-media);
  color: white;
  padding: var(--space-3-5) var(--space-4-5);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-overlay);
  z-index: 9999;
  min-width: 320px;
  max-width: 480px;
}
.install-info { display: flex; align-items: center; gap: var(--space-3); }
.install-icon { font-size: var(--text-xl); color: var(--color-primary); }
.install-title { font-size: var(--text-sm); font-weight: var(--weight-semibold); }
.install-sub   { font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-0-5); }
.install-actions { display: flex; align-items: center; gap: var(--space-2); }
.install-btn {
  padding: var(--space-1-5) var(--space-4); border-radius: var(--radius-sm);
  background: var(--color-primary); color: var(--color-text-on-primary); border: none;
  font-size: var(--text-sm); font-weight: var(--weight-medium); cursor: pointer;
  transition: background var(--duration-base);
}
.install-btn:hover { background: var(--color-primary-hover); }
.dismiss-btn {
  background: none; border: none; color: var(--color-text-secondary);
  cursor: pointer; font-size: var(--text-sm); padding: var(--space-1-5);
  border-radius: var(--radius-sm); transition: color var(--duration-base);
}
.dismiss-btn:hover { color: white; }

.slide-up-enter-active, .slide-up-leave-active { transition: var(--transition-interactive); }
.slide-up-enter-from, .slide-up-leave-to { transform: translateX(-50%) translateY(80px); opacity: 0; }
</style>
