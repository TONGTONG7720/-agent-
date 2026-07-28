<template>
  <div>
    <div class="head">
      <h2 style="margin:0">项目</h2>
      <el-button type="primary" @click="createDlg = true">新建项目</el-button>
    </div>

    <el-row :gutter="16">
      <el-col v-for="p in projects" :key="p.id" :span="8" style="margin-bottom:16px">
        <el-card shadow="hover">
          <template #header>
            <div class="card-head">
              <span>{{ p.name }}</span>
              <el-button size="small" type="primary" plain @click="openTaskDlg(p)">发起任务</el-button>
            </div>
          </template>
          <el-table :data="tasksOf(p.id)" size="small" @row-click="(row: Task) => router.push(`/tasks/${row.id}`)"
                    style="cursor:pointer">
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="requirement" label="需求" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-if="projects.length === 0" description="还没有项目，点击右上角创建" />

    <el-dialog v-model="createDlg" title="新建项目" width="420px">
      <el-input v-model="newProjectName" placeholder="项目名称" />
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="primary" @click="onCreateProject">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="taskDlg" :title="`发起任务 · ${currentProject?.name ?? ''}`" width="560px">
      <el-input v-model="requirement" type="textarea" :rows="5"
                placeholder="用一句话或一段话描述你要开发的需求，例如：写一个Python计算器，支持加减乘除和括号" />
      <div style="margin-top:12px">
        <el-switch v-model="autoMode" active-text="全自动模式（跳过人工审批）" />
      </div>
      <template #footer>
        <el-button @click="taskDlg = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreateTask">启动多Agent协作</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type Project, type Task } from '../api'
import { statusText, statusType } from '../utils/status'

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
  return tasks.value.filter((t) => t.projectId === projectId).slice(0, 5)
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
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
