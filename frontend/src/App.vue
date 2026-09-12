<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { BookOpen, LogOut } from 'lucide-vue-next';
import { auth, logout } from './lib/auth';
import { errorMessage } from './lib/api';
const route = useRoute();
const router = useRouter();
const error = ref('');
async function signOut() {
  try {
    await logout();
    await router.push('/');
  } catch (e) {
    error.value = errorMessage(e);
  }
}
</script>
<template>
  <a class="skip-link" href="#main-content">跳转到主要内容</a>
  <header v-if="!route.meta.reader" class="site-header">
    <RouterLink to="/" class="brand" aria-label="云上书房首页"
      ><span class="brand-icon"><BookOpen :size="23" :stroke-width="1.5" /></span
      ><span>云上书房<small>A PERSONAL READING ROOM</small></span></RouterLink
    >
    <nav aria-label="主导航">
      <RouterLink to="/" :class="{ selected: route.name === 'book' }">书库</RouterLink
      ><RouterLink to="/journal">阅读足迹</RouterLink
      ><RouterLink :to="auth.authenticated ? '/admin' : '/login'">{{
        auth.authenticated ? '管理书房' : '主人入口'
      }}</RouterLink
      ><button
        v-if="auth.authenticated"
        class="icon-button"
        title="退出登录"
        aria-label="退出登录"
        @click="signOut"
      >
        <LogOut :size="17" />
      </button>
    </nav>
  </header>
  <main id="main-content">
    <div v-if="error || auth.error" class="page notice error" role="alert">
      {{ error || auth.error }}
    </div>
    <RouterView :key="String(route.params.id ?? '') + ':' + auth.authenticated" />
  </main>
  <footer v-if="!route.meta.reader" class="site-footer">
    <span>云上书房 <span class="footer-dot">·</span> 让阅读有迹可循</span
    ><span>不赶路，也不辜负每一页。</span>
  </footer>
</template>
