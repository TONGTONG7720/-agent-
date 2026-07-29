<template>
  <div>
    <div class="page-head">
      <div>
        <h2>我的项目</h2>
        <div class="sub">{{ projects.length }} 个项目 · {{ tasks.length }} 个任务</div>
      </div>
      <el-button type="primary" size="large" @click="createDlg = true">新建项目</el-button>
    </div>

    <el-row :gutter="20">
      <el-col v-for="p in projects" :key="p.id" :xs="24" :md="12" :lg="8" style="margin-bottom:20px">
        <el-card shadow="never" class="hoverable sticker-card pop-in">
          <template #header>
            <div class="card-head">
              <span class="proj-name">{{ p.name }}</span>
              <span class="proj-meta">{{ tasksOf(p.id).length }} 个任务</span>
            </div>
          </template>
          <div v-for="t in tasksOf(p.id)" :key="t.id" class="task-row"
               @click="router.push(`/tasks/${t.id}`)">
            <span class="no">#{{ t.id }}</span>
            <span class="req">{{ t.requirement }}</span>
            <span :class="statusClass(t.status)">{{ statusText(t.status) }}</span>
          </div>
          <div v-if="tasksOf(p.id).length === 0" class="no-task">还没有任务</div>
          <el-button class="new-task-btn" @click="openTaskDlg(p)">发起新任务</el-button>
        </el-card>
      </el-col>
    </el-row>

    <div v-if="projects.length === 0" class="empty-wrap pop-in">
      <img src="../assets/empty-state.png" alt="空状态" class="empty-img float-soft" />
      <div class="empty-text">还没有项目，点右上角创建第一个</div>
    </div>

    <el-dialog v-model="createDlg" title="新建项目" width="420px">
      <el-input v-model="newProjectName" placeholder="项目名称" />
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="primary" @click="onCreateProject">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="taskDlg" :title="`发起任务 · ${currentProject?.name ?? ''}`" width="560px">
      <el-input v-model="requirement" type="textarea" :rows="5"
                placeholder="描述你想做什么，例如：写一个Python计算器，支持加减乘除和括号" />
      <div style="margin-top:12px">
        <el-switch v-model="autoMode" active-text="全自动模式（跳过人工审批）" />
      </div>
      <template #footer>
        <el-button @click="taskDlg = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreateTask">启动协作</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type Project, type Task } from '../api'
import { statusClass, statusText } from '../utils/status'

const router = useRouter()
const projects = ref<Project[]>([])
const tasks = ref<Task[]>([])
const createDlg = ref(false)
const newProjectName = ref('')
const taskDlg = ref(false)
const currentProject = ref<Project | null>(null)
const requirement = ref('')
const autoMode = ref(false)
const creating = ref(false)

function tasksOf(projectId: number): Task[] {
  return tasks.value.filter((t) => t.projectId === projectId).slice(0, 6)
}

async function load() {
  projects.value = await api.listProjects()
  const all: Task[] = []
  for (const p of projects.value) {
    all.push(...(await api.listTasks(p.id)))
  }
  tasks.value = all
}

function openTaskDlg(p: Project) {
  currentProject.value = p
  requirement.value = ''
  taskDlg.value = true
}

async function onCreateProject() {
  if (!newProjectName.value.trim()) return
  await api.createProject(newProjectName.value.trim())
  createDlg.value = false
  newProjectName.value = ''
  await load()
}

async function onCreateTask() {
  if (!requirement.value.trim() || !currentProject.value) return
  creating.value = true
  try {
    const task = await api.createTask(currentProject.value.id, requirement.value.trim(), autoMode.value)
    taskDlg.value = false
    router.push(`/tasks/${task.id}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.proj-name { font-size: 16px; }
.proj-meta {
  font-size: 12px;
  color: hsl(250 12% 55%);
  font-family: var(--font-body);
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.no-task {
  text-align: center;
  color: hsl(250 12% 55%);
  font-size: 13px;
  padding: 10px 0;
}
.new-task-btn {
  width: 100%;
  margin-top: 4px;
  border-style: dashed !important;
  color: hsl(var(--c-primary-deep));
}
.empty-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 0;
}
.empty-img {
  width: 240px;
  border-radius: 24px;
  border: var(--border-cartoon);
  box-shadow: var(--shadow-sticker);
  background: hsl(var(--c-paper));
}
.empty-text { margin-top: 18px; font-size: 15px; color: hsl(250 12% 45%); }
</style>
