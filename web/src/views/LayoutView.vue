<template>
  <el-container style="height:100%">
    <el-header class="topbar">
      <span class="brand" @click="router.push('/projects')">
        <img src="../assets/logo.png" alt="logo" class="brand-icon" /> 多Agent协同开发平台
      </span>
      <div class="right">
        <el-button v-if="auth.isAdmin" class="nav-btn" text @click="router.push('/admin')">
          系统管理
        </el-button>
        <el-dropdown>
          <span class="user-chip">{{ auth.username }}<el-icon><ArrowDown /></el-icon></span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="onLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>
    <el-main class="main dotted-bg">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

function onLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.topbar {
  background: hsl(var(--c-paper));
  border-bottom: var(--border-cartoon);
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 62px;
}
.brand {
  font-family: var(--font-display);
  font-size: 19px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.brand-icon {
  width: 38px;
  height: 38px;
  object-fit: cover;
  border: 2px solid hsl(var(--c-ink));
  border-radius: 12px;
  box-shadow: 2px 2px 0 hsl(var(--c-ink) / .85);
  background: hsl(var(--c-paper));
}
.right { display: flex; align-items: center; gap: 10px; }
.nav-btn { font-size: 15px; }
.user-chip {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  background: hsl(var(--c-primary) / .12);
  border: 2px solid hsl(var(--c-ink) / .8);
  border-radius: var(--radius-pill);
  font-weight: 700;
}
.main { padding: 20px 28px; }
</style>
