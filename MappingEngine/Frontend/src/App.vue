<template>
  <div class="app-shell">

    <!-- ── Topbar ──────────────────────────────────────── -->
    <header class="topbar">
      <div class="topbar-brand">
        <img :src="logoUrl" alt="ASM" class="topbar-logo" />
        <div>
          <span class="topbar-name">MappingEngine</span>
          <span class="topbar-sub">All Soft Multimedia</span>
        </div>
      </div>

      <!-- Step indicator -->
      <nav class="step-track">
        <div
          v-for="s in STEPS" :key="s.num"
          class="step-node"
          :class="{ active: state.step === s.num, done: state.step > s.num }"
        >
          <div class="step-circ">
            <svg v-if="state.step > s.num" width="12" height="12" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
            <span v-else>{{ s.num }}</span>
          </div>
          <span class="step-lbl">{{ s.label }}</span>
        </div>
      </nav>

      <div class="topbar-provider" :class="{ 'has-name': state.providerName }">
        {{ state.providerName || 'New Integration' }}
      </div>
    </header>

    <!-- ── Step viewport ───────────────────────────────── -->
    <div class="step-viewport">
      <Transition :name="state.direction >= 1 ? 'step-fwd' : 'step-bwd'" mode="out-in">
        <div class="step-view" :key="state.step">
          <StepWelcome v-if="state.step === 1" />
          <StepMapping v-else-if="state.step === 2" />
          <StepVerify  v-else-if="state.step === 3" />
          <StepDone    v-else-if="state.step === 4" />
        </div>
      </Transition>
    </div>

    <!-- ── Toast stack ──────────────────────────────────── -->
    <div class="toast-stack">
      <TransitionGroup name="toast">
        <div
          v-for="t in toasts" :key="t.id"
          class="toast"
          :class="`toast-${t.type}`"
        >{{ t.msg }}</div>
      </TransitionGroup>
    </div>

  </div>
</template>

<script setup>
import { useMapping } from './composables/useMapping.js'
import StepWelcome from './components/StepWelcome.vue'
import StepMapping from './components/StepMapping.vue'
import StepVerify  from './components/StepVerify.vue'
import StepDone    from './components/StepDone.vue'
import logoUrl     from '../avatar.png'

const { state, toasts } = useMapping()

const STEPS = [
  { num: 1, label: 'Setup'   },
  { num: 2, label: 'Mapping' },
  { num: 3, label: 'Verify'  },
  { num: 4, label: 'Done'    },
]
</script>
