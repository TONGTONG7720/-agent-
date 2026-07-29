<template>
  <div class="login-wrap dotted-bg">
    <div class="login-box pop-in">
      <!-- 左侧插画 -->
      <div class="hero-side">
        <img src="../assets/login-hero.png" alt="多Agent团队" class="hero-img float-soft" />
        <div class="hero-caption">五只小机器人，组队帮你写代码 🚀</div>
      </div>

      <!-- 右侧登录卡 -->
      <div class="form-side">
        <div class="brand-badge wiggle">🤖</div>
        <h1 class="title">多Agent协同开发平台</h1>
        <p class="subtitle">提个需求，让 AI 小队分工搞定它～</p>

        <el-input v-model="username" placeholder="用户名" size="large" class="fld">
          <template #prefix><span>👤</span></template>
        </el-input>
        <el-input v-model="password" type="password" placeholder="密码" size="large"
                  show-password class="fld" @keyup.enter="onLogin">
          <template #prefix><span>🔑</span></template>
        </el-input>

        <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="onLogin">
          开始协作 ✨
        </el-button>
        <div class="tip">默认账号：admin / admin123</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function onLogin() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    router.push('/projects')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  box-sizing: border-box;
}
.login-box {
  display: flex;
  width: 860px;
  max-width: 100%;
  background: hsl(var(--c-paper));
  border: var(--border-cartoon);
  border-radius: 28px;
  box-shadow: 8px 8px 0 hsl(var(--c-ink) / .9);
  overflow: hidden;
}
.hero-side {
  width: 46%;
  background: var(--gradient-candy);
  padding: 28px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.hero-img {
  width: 100%;
  max-width: 320px;
  border-radius: 20px;
  border: var(--border-cartoon);
  box-shadow: 4px 4px 0 hsl(var(--c-ink) / .9);
  background: hsl(var(--c-paper));
}
.hero-caption {
  color: hsl(var(--c-paper));
  font-size: 16px;
  text-align: center;
  text-shadow: 1.5px 1.5px 0 hsl(var(--c-ink) / .5);
}
.form-side {
  flex: 1;
  padding: 44px 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.brand-badge {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: hsl(var(--c-yellow) / .5);
  border: var(--border-cartoon);
  box-shadow: 3px 3px 0 hsl(var(--c-ink) / .9);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  margin-bottom: 14px;
}
.title { font-size: 24px; margin: 0 0 6px; color: hsl(var(--c-ink)); }
.subtitle { color: hsl(250 12% 48%); margin: 0 0 26px; font-size: 14px; }
.fld { margin-bottom: 16px; }
.login-btn { width: 100%; font-size: 17px; height: 48px; margin-top: 6px; }
.tip {
  margin-top: 18px;
  font-size: 13px;
  color: hsl(250 12% 55%);
  background: hsl(var(--c-primary) / .1);
  padding: 6px 14px;
  border-radius: var(--radius-pill);
}
@media (max-width: 720px) {
  .hero-side { display: none; }
}
</style>
