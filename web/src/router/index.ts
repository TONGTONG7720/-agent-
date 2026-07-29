import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    {
      path: '/',
      component: () => import('../views/LayoutView.vue'),
      children: [
        { path: '', redirect: '/projects' },
        { path: 'projects', component: () => import('../views/ProjectsView.vue') },
        { path: 'tasks/:id', component: () => import('../views/TaskView.vue') },
        { path: 'compare/:idA/:idB', component: () => import('../views/CompareView.vue') },
        { path: 'admin', component: () => import('../views/AdminView.vue'), meta: { admin: true } }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.path !== '/login' && !auth.isLoggedIn) {
    return '/login'
  }
  if (to.meta.admin && !auth.isAdmin) {
    return '/projects'
  }
  return true
})

export default router
