import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
export default defineConfig({plugins:[vue()],server:{host:"127.0.0.1",port:5174,strictPort:true,
  proxy:{"/api":"http://127.0.0.1:8081"}},test:{environment:"node",pool:"threads",include:["tests/**/*.test.ts"]}});
