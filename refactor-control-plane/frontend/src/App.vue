<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { fetchProjectOverview, type ProjectOverview } from "./control-plane-api";
import ArticleWorkspace from "./article/ArticleWorkspace.vue";
import {
  getRoleWorkspace,
  roles,
  type RoleId
} from "./role-workbench";

const props = withDefaults(defineProps<{ initialRole?: RoleId }>(), {
  initialRole: "project-manager"
});

const selectedRole = ref<RoleId>(props.initialRole);
const currentRole = computed(() => getRoleWorkspace(selectedRole.value));
const connectionState = ref<"connected" | "disconnected">("disconnected");
const remoteOverview = ref<ProjectOverview>();

onMounted(async () => {
  try {
    remoteOverview.value = await fetchProjectOverview();
    connectionState.value = "connected";
  } catch {
    connectionState.value = "disconnected";
  }
});

const metrics = [
  { value: "12", label: "工作包", detail: "7 个已就绪" },
  { value: "3", label: "并行执行", detail: "无路径冲突" },
  { value: "2", label: "待决策", detail: "API 与 Schema" },
  { value: "86%", label: "证据覆盖", detail: "目标 ≥ 95%" }
];

const demoWorkPackages = [
  { id: "WP-101", name: "文章查询 API", owner: "Developer A", status: "RUNNING", tone: "active" },
  { id: "WP-102", name: "文章列表界面", owner: "AI Agent B", status: "READY", tone: "ready" },
  { id: "WP-103", name: "分类 Schema 校验", owner: "Developer C", status: "BLOCKED", tone: "blocked" }
];

const workPackages = computed(() => remoteOverview.value?.plan.workPackages.slice(0, 3).map((task, index) => ({
  id: task.id,
  name: task.title,
  owner: task.role,
  status: index === 0 ? "RUNNING" : "READY",
  tone: index === 0 ? "active" : "ready"
})) ?? demoWorkPackages);

const events = [
  { time: "10:42", title: "WP-101 已续租", detail: "src/main/java/.../PostController.java" },
  { time: "10:31", title: "API Contract v3 已发布", detail: "2 个下游任务需要重新验证" },
  { time: "10:08", title: "架构决策 ADR-004 通过", detail: "采用渐进式前后端分离" }
];
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">R</span>
        <div>
          <strong>Refactor</strong>
          <span>Control Plane</span>
        </div>
      </div>

      <div class="sidebar-label">角色工作台</div>
      <nav class="role-list" aria-label="角色工作台">
        <button
          v-for="role in roles"
          :key="role.id"
          class="role-button"
          :class="{ selected: selectedRole === role.id }"
          :data-role-id="role.id"
          :aria-pressed="selectedRole === role.id"
          type="button"
          @click="selectedRole = role.id"
        >
          <span class="role-dot" aria-hidden="true"></span>
          <span>
            <strong>{{ role.label }}</strong>
            <small>{{ role.primaryView }}</small>
          </span>
        </button>
      </nav>

      <div class="system-state">
        <span class="state-indicator" aria-hidden="true"></span>
        <div>
          <strong>{{ connectionState === "connected" ? "治理服务正常" : "静态演示" }}</strong>
          <small>{{ connectionState === "connected" ? "后端已连接 · 人工合并" : "后端未连接 · 模型未连接" }}</small>
        </div>
      </div>
    </aside>

    <main class="main-content">
      <header class="topbar">
        <div>
          <div class="eyebrow">项目 / LEGACY-001</div>
          <h1>Legacy Blog Modernization</h1>
        </div>
        <div class="topbar-actions">
          <span class="stage-badge">治理基线 · Wave 01</span>
          <button class="avatar" type="button" :aria-label="`当前角色：${currentRole.label}`">
            {{ currentRole.label.slice(0, 1) }}
          </button>
        </div>
      </header>

      <section class="role-hero">
        <div>
          <div class="eyebrow">{{ currentRole.label }} / {{ currentRole.primaryView }}</div>
          <h2>{{ currentRole.headline }}</h2>
        </div>
        <div class="responsibility-list" aria-label="当前角色关注项">
          <span v-for="item in currentRole.responsibilities" :key="item">{{ item }}</span>
        </div>
      </section>

      <section class="metric-grid" aria-label="项目治理指标">
        <article v-for="metric in metrics" :key="metric.label" class="metric-card">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
          <small>{{ metric.detail }}</small>
        </article>
      </section>

      <div class="content-grid">
        <section class="panel work-panel">
          <div class="panel-heading">
            <div>
              <span class="eyebrow">执行协调</span>
              <h3>关键工作包</h3>
            </div>
            <button class="text-button" type="button" disabled>查看任务图 →</button>
          </div>

          <div class="work-table" role="table" aria-label="关键工作包">
            <div class="work-row work-header" role="row">
              <span role="columnheader">工作包</span>
              <span role="columnheader">执行者</span>
              <span role="columnheader">状态</span>
            </div>
            <div v-for="workPackage in workPackages" :key="workPackage.id" class="work-row" role="row">
              <span role="cell">
                <small>{{ workPackage.id }}</small>
                <strong>{{ workPackage.name }}</strong>
              </span>
              <span role="cell">{{ workPackage.owner }}</span>
              <span role="cell">
                <span class="status-pill" :class="workPackage.tone">{{ workPackage.status }}</span>
              </span>
            </div>
          </div>
        </section>

        <section class="panel event-panel">
          <div class="panel-heading">
            <div>
              <span class="eyebrow">Shared Context</span>
              <h3>变更事件</h3>
            </div>
          </div>
          <ol class="event-list">
            <li v-for="event in events" :key="event.time + event.title">
              <time>{{ event.time }}</time>
              <div>
                <strong>{{ event.title }}</strong>
                <span>{{ event.detail }}</span>
              </div>
            </li>
          </ol>
        </section>
      </div>
      <ArticleWorkspace />
    </main>
  </div>
</template>
