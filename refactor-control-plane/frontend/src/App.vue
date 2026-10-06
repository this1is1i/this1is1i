<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { fetchProjectOverview, type ProjectOverview } from "./control-plane-api";
import ArticleWorkspace from "./article/ArticleWorkspace.vue";
import IntegrationWorkspace from "./integration/IntegrationWorkspace.vue";
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
          <strong>{{ connectionState === "connected" ? "项目概览已读取" : "概览未连接" }}</strong>
          <small>{{ connectionState === "connected" ? "操作前请连接项目身份" : "后端未连接 · 无运行状态" }}</small>
        </div>
      </div>
    </aside>

    <main class="main-content">
      <header class="topbar">
        <div>
          <div class="eyebrow">项目 / {{ remoteOverview?.projectId ?? '尚未读取' }}</div>
          <h1>Legacy Blog Modernization</h1>
        </div>
        <div class="topbar-actions">
          <span class="stage-badge">多任务集成 · 人工合并</span>
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

      <IntegrationWorkspace />
      <ArticleWorkspace />
    </main>
  </div>
</template>
