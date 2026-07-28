<template>
  <el-container style="height:100%">
    <el-header class="topbar">
      <span class="brand" @click="router.push('/projects')">🤖 多Agent协同开发平台</span>
      <div>
        <el-button v-if="auth.isAdmin" text @click="router.push('/admin')">系统管理</el-button>
        <el-dropdown>
          <span class="user">{{ auth.username }}<el-icon><ArrowDown /></el-icon></span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="onLogout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>
    <el-main style="padding:16px 24px">
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
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.brand {
  font-weight: 600;
  font-size: 16px;
  cursor: pointer;
}
.user {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 12px;
}
</style>
