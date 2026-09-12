import { reactive } from 'vue';
import { errorMessage, request, resetCsrf } from './api';
export const auth = reactive({ authenticated: false, username: '', ready: false, error: '' });
let initializing: Promise<void> | undefined;
export function loadAuth(force = false): Promise<void> {
  if (auth.ready && !force) return Promise.resolve();
  if (initializing) return initializing;
  initializing = (async () => {
    try {
      const me = await request<{ authenticated: boolean; username: string }>('/api/auth/me');
      Object.assign(auth, me, { error: '' });
    } catch (e) {
      auth.authenticated = false;
      auth.error = errorMessage(e);
    } finally {
      auth.ready = true;
      initializing = undefined;
    }
  })();
  return initializing;
}
export async function login(username: string, password: string) {
  await request('/api/auth/login', {
    method: 'POST',
    body: new URLSearchParams({ username, password }),
  });
  resetCsrf();
  await loadAuth(true);
  if (!auth.authenticated) throw new Error(auth.error || '登录状态获取失败，请重试。');
}
export async function logout() {
  await request('/api/auth/logout', { method: 'POST' });
  resetCsrf();
  Object.assign(auth, { authenticated: false, username: '', error: '' });
}
