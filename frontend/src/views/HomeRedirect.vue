<!--
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 -->
<template>
  <div class="home-redirect" v-loading="true" aria-label="正在进入 SemEvoSQL"></div>
</template>

<script setup lang="ts">
  import { onMounted } from 'vue';
  import { useRouter } from 'vue-router';
  import { platformContext } from '@/services/platformContext';
  import { defaultHomeForRole } from '@/services/projectCapabilities.mjs';

  const router = useRouter();

  onMounted(async () => {
    try {
      const operator = await platformContext.operator();
      await router.replace(defaultHomeForRole(operator.role));
    } catch {
      await router.replace('/projects');
    }
  });
</script>

<style scoped>
  .home-redirect {
    min-height: 100vh;
  }
</style>
