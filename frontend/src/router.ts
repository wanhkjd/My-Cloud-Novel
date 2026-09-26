import { createRouter, createWebHistory } from 'vue-router';
import { auth, loadAuth } from './lib/auth';
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/',
      name: 'galaxy',
      component: () => import('./views/GalaxyView.vue'),
      meta: { title: '星河', galaxy: true },
    },
    {
      path: '/books/:id',
      name: 'book',
      component: () => import('./views/BookView.vue'),
      meta: { title: '藏书详情' },
    },
    {
      path: '/read/:id/:chapter',
      name: 'reader',
      component: () => import('./views/ReaderView.vue'),
      meta: { title: '阅读', reader: true },
    },
    {
      path: '/admin',
      component: () => import('./views/AdminView.vue'),
      meta: { title: '管理书房', owner: true },
    },
    {
      path: '/login',
      component: () => import('./views/LoginView.vue'),
      meta: { title: '主人入口' },
    },
    {
      path: '/:pathMatch(.*)*',
      component: () => import('./views/NotFoundView.vue'),
      meta: { title: '未找到页面' },
    },
  ],
});
router.beforeEach(async (to) => {
  await loadAuth();
  if (to.meta.owner && !auth.authenticated) return { path: '/login', query: { next: to.fullPath } };
});
router.afterEach((to) => {
  document.title = String(to.meta.title ?? '云上书房') + ' · 云上书房';
});
export default router;
