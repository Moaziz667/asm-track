<template>
  <div class="done-page">
    <div class="done-inner">

      <!-- Bauhaus decoration -->
      <div class="done-bg" aria-hidden="true">
        <div class="done-ring r1"></div>
        <div class="done-ring r2"></div>
        <div class="done-ring r3"></div>
      </div>

      <!-- Success mark -->
      <div class="success-mark" :class="{ animate: mounted }">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none"
          stroke="#000" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12"/>
        </svg>
      </div>

      <!-- Message -->
      <div class="done-message" :class="{ visible: mounted }">
        <h1 class="done-title">
          Integration <span class="text-orange">{{ state.providerName }}</span><br/>
          is ready.
        </h1>
        <p class="done-sub">
          {{ successCount }} mapping rule{{ successCount !== 1 ? 's' : '' }} have been saved to the database
          and will be applied to every incoming {{ state.providerName }} payload.
        </p>
      </div>

      <!-- Stats row -->
      <div class="stats-row" :class="{ visible: mounted }">
        <div class="stat-card">
          <div class="stat-val text-orange">{{ successCount }}</div>
          <div class="stat-lbl">Rules saved</div>
        </div>
        <div class="stat-card">
          <div class="stat-val">{{ errorCount }}</div>
          <div class="stat-lbl">Errors</div>
        </div>
        <div class="stat-card">
          <div class="stat-val">{{ canonicalCoverage }}%</div>
          <div class="stat-lbl">Canonical coverage</div>
        </div>
        <div class="stat-card">
          <div class="stat-val">{{ fieldsCovered }}</div>
          <div class="stat-lbl">Fields mapped</div>
        </div>
      </div>

      <!-- Results breakdown (if any errors) -->
      <div v-if="errorCount > 0" class="error-list">
        <div class="error-list-title">Failed rules:</div>
        <div
          v-for="r in state.saveResults.filter(r => !r.ok)"
          :key="r.mapping.id"
          class="error-item"
        >
          <span class="font-mono">{{ r.mapping.name }}</span>
          <span class="badge badge-red">{{ r.error }}</span>
        </div>
      </div>

      <!-- CTA buttons -->
      <div class="done-actions" :class="{ visible: mounted }">
        <button class="btn btn-orange btn-xl" @click="startNew">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          Map another provider
        </button>
        <a href="/api/mappings" target="_blank" class="btn btn-ghost">
          View all rules ↗
        </a>
      </div>

    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useMapping }               from '../composables/useMapping.js'
import { CANONICAL_FIELDS }         from '../data/canonical.js'

const { state, reset } = useMapping()

const mounted = ref(false)
onMounted(() => setTimeout(() => { mounted.value = true }, 80))

const successCount = computed(() => state.saveResults.filter(r => r.ok).length)
const errorCount   = computed(() => state.saveResults.filter(r => !r.ok).length)
const fieldsCovered = computed(() =>
  new Set(state.saveResults.filter(r => r.ok).map(r => r.mapping.canonicalField)).size
)
const canonicalCoverage = computed(() =>
  Math.round((fieldsCovered.value / CANONICAL_FIELDS.length) * 100)
)

function startNew() {
  reset()
}
</script>

<style scoped>
.done-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  overflow: hidden;
  position: relative;
}

/* Bauhaus rings */
.done-bg { position: absolute; inset: 0; pointer-events: none; overflow: hidden; }
.done-ring {
  position: absolute;
  border-radius: 50%;
  border: 1px solid rgba(249,115,22,.07);
  top: 50%;
  left: 50%;
  transform: translate(-50%,-50%);
}
.r1 { width: 400px; height: 400px; }
.r2 { width: 650px; height: 650px; border-color: rgba(249,115,22,.04); }
.r3 { width: 900px; height: 900px; border-color: rgba(249,115,22,.02); }

/* Inner */
.done-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 32px;
  max-width: 660px;
  width: 100%;
  position: relative;
  z-index: 1;
}

/* Success mark */
.success-mark {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: var(--o);
  display: flex;
  align-items: center;
  justify-content: center;
  transform: scale(0) rotate(-180deg);
  opacity: 0;
  transition: transform .5s cubic-bezier(.17,.67,.35,1.4), opacity .3s;
  box-shadow: 0 0 0 0 var(--o-ring);
}
.success-mark.animate {
  transform: scale(1) rotate(0deg);
  opacity: 1;
  box-shadow: 0 0 0 20px rgba(249,115,22,0);
  animation: pulse-ring .8s .4s ease-out;
}
@keyframes pulse-ring {
  0%   { box-shadow: 0 0 0 0 rgba(249,115,22,.45); }
  100% { box-shadow: 0 0 0 28px rgba(249,115,22,0); }
}

/* Message */
.done-message {
  opacity: 0;
  transform: translateY(16px);
  transition: opacity .4s .3s, transform .4s .3s;
}
.done-message.visible { opacity: 1; transform: translateY(0); }
.done-title {
  font-family: var(--f-head);
  font-size: clamp(28px, 4vw, 42px);
  font-weight: 800;
  color: var(--tx);
  letter-spacing: -1px;
  line-height: 1.15;
  margin-bottom: 14px;
}
.done-sub {
  font-size: 15px;
  color: var(--tx-muted);
  line-height: 1.7;
  max-width: 500px;
}

/* Stats */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  width: 100%;
  opacity: 0;
  transform: translateY(16px);
  transition: opacity .4s .5s, transform .4s .5s;
}
.stats-row.visible { opacity: 1; transform: translateY(0); }

.stat-card {
  background: var(--s1);
  border: 1px solid var(--bd);
  border-radius: var(--r);
  padding: 20px 16px;
  text-align: center;
}
.stat-val {
  font-family: var(--f-head);
  font-size: 28px;
  font-weight: 800;
  color: var(--tx);
  letter-spacing: -.5px;
  line-height: 1;
  margin-bottom: 6px;
}
.stat-lbl {
  font-size: 11px;
  font-weight: 600;
  color: var(--tx-muted);
  font-family: var(--f-head);
  text-transform: uppercase;
  letter-spacing: .5px;
}

/* Error list */
.error-list {
  width: 100%;
  background: var(--red-d);
  border: 1px solid rgba(239,68,68,.2);
  border-radius: var(--r);
  padding: 16px;
  text-align: left;
}
.error-list-title {
  font-size: 11px;
  font-weight: 700;
  font-family: var(--f-head);
  color: var(--red);
  text-transform: uppercase;
  letter-spacing: .5px;
  margin-bottom: 10px;
}
.error-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: var(--tx);
  padding: 5px 0;
  border-bottom: 1px solid rgba(239,68,68,.1);
}
.error-item:last-child { border-bottom: none; }

/* Actions */
.done-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  opacity: 0;
  transform: translateY(12px);
  transition: opacity .4s .7s, transform .4s .7s;
}
.done-actions.visible { opacity: 1; transform: translateY(0); }
.done-actions a { text-decoration: none; }
</style>
