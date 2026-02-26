<template>
  <div class="verify-page">
    <div class="verify-inner">

      <!-- Header -->
      <div class="verify-header">
        <div>
          <div class="eyebrow">Step 3 of 4 — Review</div>
          <h2 class="verify-title">
            Verify your <span class="text-orange">{{ state.pendingMappings.length }}</span> mapping rules
          </h2>
          <p class="verify-sub">
            Check each rule carefully. All must be verified before you can save.
          </p>
        </div>

        <!-- Progress -->
        <div class="progress-block">
          <div class="progress-fraction">
            <span class="progress-n">{{ verifiedIds.size }}</span>
            <span class="progress-sep">/</span>
            <span class="progress-d">{{ state.pendingMappings.length }}</span>
          </div>
          <div class="progress-bar-wrap">
            <div class="progress-bar-fill" :style="{ width: progressPct + '%' }"></div>
          </div>
          <div class="progress-label">{{ progressPct === 100 ? 'All verified ✓' : `${progressPct}% verified` }}</div>
        </div>
      </div>

      <!-- Rules grid -->
      <div class="rules-grid">
        <div
          v-for="m in state.pendingMappings"
          :key="m.id"
          class="rule-card"
          :class="{ verified: verifiedIds.has(m.id) }"
          @click="toggleVerified(m.id)"
        >
          <!-- Verified indicator -->
          <div class="rule-check">
            <Transition name="check-pop">
              <svg v-if="verifiedIds.has(m.id)" width="16" height="16" viewBox="0 0 24 24"
                fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="20 6 9 17 4 12"/>
              </svg>
              <span v-else>{{ state.pendingMappings.indexOf(m) + 1 }}</span>
            </Transition>
          </div>

          <!-- Rule body -->
          <div class="rule-body">
            <div class="rule-flow">
              <!-- Source -->
              <div class="rule-field source">
                <div class="rule-field-label">SOURCE</div>
                <div class="rule-field-val">
                  {{ m.staticValue ? `"${m.staticValue}"` : m.sourceField }}
                </div>
                <span v-if="m.staticValue" class="badge badge-gray" style="font-size:9px">static</span>
              </div>

              <!-- Arrow -->
              <div class="rule-field-arrow">
                <span v-if="m.transformationType" class="transform-pill">{{ m.transformationType }}</span>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="5" y1="12" x2="19" y2="12"/>
                  <polyline points="12 5 19 12 12 19"/>
                </svg>
              </div>

              <!-- Canonical -->
              <div class="rule-field canonical">
                <div class="rule-field-label">CANONICAL</div>
                <div class="rule-field-val canonical-val">{{ m.canonicalField }}</div>
              </div>
            </div>

            <!-- Enum pills -->
            <div v-if="m.enumMapping && Object.keys(m.enumMapping).length" class="enum-preview">
              <span
                v-for="(v, k) in m.enumMapping"
                :key="k"
                class="badge badge-yellow"
                style="font-size:9px"
              >{{ k }} → {{ v }}</span>
            </div>
          </div>

          <!-- Delete -->
          <button
            class="rule-del"
            @click.stop="removeAndDeselect(m.id)"
            title="Remove rule"
          >×</button>
        </div>
      </div>

      <!-- Empty state -->
      <div v-if="!state.pendingMappings.length" class="empty-state">
        <p>No mapping rules added. <button class="link-btn" @click="goTo(2)">← Go back to mapping</button></p>
      </div>

      <!-- Actions -->
      <div class="verify-actions">
        <button class="btn btn-ghost" @click="goTo(2)">← Back to mapping</button>
        <button
          class="btn btn-green btn-xl"
          :disabled="!allVerified || saving"
          @click="doSave"
        >
          <span v-if="saving">Saving…</span>
          <template v-else>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/>
              <polyline points="17 21 17 13 7 13 7 21"/>
              <polyline points="7 3 7 8 15 8"/>
            </svg>
            Save {{ state.pendingMappings.length }} rules to database
          </template>
        </button>
      </div>

    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useMapping }    from '../composables/useMapping.js'

const { state, toast, goTo, removeMapping, saveAll } = useMapping()

const verifiedIds = ref(new Set())
const saving      = ref(false)

const progressPct = computed(() => {
  if (!state.pendingMappings.length) return 0
  return Math.round((verifiedIds.value.size / state.pendingMappings.length) * 100)
})

const allVerified = computed(() =>
  state.pendingMappings.length > 0 &&
  state.pendingMappings.every(m => verifiedIds.value.has(m.id))
)

function toggleVerified(id) {
  const next = new Set(verifiedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  verifiedIds.value = next
}

function removeAndDeselect(id) {
  removeMapping(id)
  const next = new Set(verifiedIds.value)
  next.delete(id)
  verifiedIds.value = next
}

async function doSave() {
  if (!allVerified.value) return
  saving.value = true
  const results = await saveAll()
  saving.value  = false
  const ok  = results.filter(r => r.ok).length
  const bad = results.filter(r => !r.ok).length
  if (bad > 0) {
    toast(`${ok} saved, ${bad} failed.`, 'error')
  } else {
    toast(`${ok} rules saved successfully!`, 'success')
    goTo(4)
  }
}
</script>

<style scoped>
.verify-page {
  min-height: 100%;
  padding: 32px 24px 48px;
  overflow-y: auto;
}
.verify-inner {
  max-width: 900px;
  margin: 0 auto;
}

/* Header */
.eyebrow {
  font-size: 11px;
  font-weight: 700;
  font-family: var(--f-head);
  text-transform: uppercase;
  letter-spacing: .8px;
  color: var(--o);
  margin-bottom: 8px;
}
.verify-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 40px;
  margin-bottom: 32px;
}
.verify-title {
  font-family: var(--f-head);
  font-size: 28px;
  font-weight: 800;
  color: var(--tx);
  letter-spacing: -.5px;
  margin-bottom: 8px;
}
.verify-sub {
  font-size: 13px;
  color: var(--tx-muted);
  line-height: 1.6;
}

/* Progress */
.progress-block { flex-shrink: 0; text-align: right; }
.progress-fraction {
  font-family: var(--f-head);
  font-size: 32px;
  font-weight: 800;
  color: var(--tx);
  line-height: 1;
  margin-bottom: 8px;
}
.progress-n   { color: var(--o); }
.progress-sep { color: var(--tx-faint); font-size: 22px; margin: 0 3px; }
.progress-bar-wrap {
  width: 180px;
  height: 4px;
  background: var(--s3);
  border-radius: 99px;
  overflow: hidden;
  margin-bottom: 6px;
  margin-left: auto;
}
.progress-bar-fill {
  height: 100%;
  background: var(--o);
  border-radius: 99px;
  transition: width .35s cubic-bezier(.4,0,.2,1);
}
.progress-label {
  font-size: 11px;
  font-weight: 700;
  font-family: var(--f-head);
  color: var(--tx-muted);
  letter-spacing: .2px;
}

/* Rules grid */
.rules-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 12px;
  margin-bottom: 32px;
}

.rule-card {
  background: var(--s1);
  border: 1px solid var(--bd);
  border-radius: var(--r);
  padding: 16px 16px 16px 60px;
  position: relative;
  cursor: pointer;
  transition: border-color .2s, background .2s, box-shadow .2s;
}
.rule-card:hover { border-color: var(--bd-hi); background: var(--s2); }
.rule-card.verified {
  border-color: rgba(34,197,94,.4);
  background: rgba(34,197,94,.05);
  box-shadow: 0 0 0 1px rgba(34,197,94,.15);
}

.rule-check {
  position: absolute;
  left: 16px;
  top: 16px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 2px solid var(--bd-hi);
  background: var(--s2);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--f-head);
  font-size: 11px;
  font-weight: 700;
  color: var(--tx-muted);
  transition: border-color .2s, background .2s, color .2s;
}
.rule-card.verified .rule-check {
  border-color: var(--green);
  background: var(--green);
  color: #000;
}

.check-pop-enter-active { transition: transform .2s cubic-bezier(.17,.67,.35,1.4), opacity .15s; }
.check-pop-enter-from   { transform: scale(0); opacity: 0; }

.rule-body { flex: 1; }
.rule-flow {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.rule-field { flex: 1; min-width: 0; }
.rule-field-label {
  font-size: 9px;
  font-weight: 800;
  font-family: var(--f-head);
  letter-spacing: .8px;
  color: var(--tx-muted);
  margin-bottom: 3px;
  text-transform: uppercase;
}
.rule-field-val {
  font-family: var(--f-mono);
  font-size: 12px;
  color: var(--tx);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rule-field.source .rule-field-val    { color: var(--o); }
.canonical-val                        { color: var(--green); }

.rule-field-arrow {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  color: var(--tx-muted);
}
.transform-pill {
  font-size: 9px;
  font-weight: 700;
  font-family: var(--f-head);
  background: var(--yellow-d);
  color: var(--yellow);
  border-radius: 99px;
  padding: 1px 6px;
  letter-spacing: .3px;
}

.enum-preview { display: flex; flex-wrap: wrap; gap: 4px; }

.rule-del {
  position: absolute;
  top: 12px;
  right: 12px;
  background: none;
  border: none;
  cursor: pointer;
  color: var(--tx-faint);
  font-size: 17px;
  line-height: 1;
  transition: color .15s;
  padding: 2px 5px;
  border-radius: 4px;
}
.rule-del:hover { color: var(--red); background: var(--red-d); }

/* Actions */
.verify-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 8px;
}

/* Empty */
.empty-state {
  text-align: center;
  padding: 48px;
  color: var(--tx-muted);
  font-size: 14px;
}
.link-btn {
  background: none;
  border: none;
  color: var(--o);
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  font-family: var(--f-body);
}
.link-btn:hover { text-decoration: underline; }
</style>
