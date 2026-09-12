<script setup lang="ts">
import { ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { KeyRound, ArrowRight } from 'lucide-vue-next';
import { login } from '../lib/auth';
import { errorMessage } from '../lib/api';
const username = ref('admin');
const password = ref('');
const busy = ref(false);
const error = ref('');
const router = useRouter();
const route = useRoute();
async function submit() {
  busy.value = true;
  error.value = '';
  try {
    await login(username.value, password.value);
    password.value = '';
    const next = String(route.query.next ?? '/admin');
    await router.push(next.startsWith('/') && !next.startsWith('//') ? next : '/admin');
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="page login-page">
    <div class="login-intro">
      <KeyRound :size="28" :stroke-width="1.3" />
      <p class="eyebrow">只为书房主人留的一把钥匙</p>
      <h1>欢迎回到书房。</h1>
      <p>管理藏书，接续阅读，整理那些不想遗忘的感想。</p>
      <span class="muted">这里没有注册入口。想读书，直接去书架就好。</span>
    </div>
    <form class="login-form panel" @submit.prevent="submit">
      <h2>主人登录</h2>
      <label
        >用户名<input v-model="username" autocomplete="username" required maxlength="100" /></label
      ><label
        >密码<input v-model="password" type="password" autocomplete="current-password" required
      /></label>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <button class="primary" :disabled="busy" type="submit">
        {{ busy ? '正在开门…' : '进入书房' }} <ArrowRight :size="17" />
      </button>
      <p class="muted small">账号密码由服务端环境变量配置。请勿将密码提交到代码仓库。</p>
    </form>
  </div>
</template>
