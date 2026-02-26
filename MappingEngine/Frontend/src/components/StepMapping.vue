<template>
  <div class="mapping-page">

    <!-- ── Top strip ───────────────────────────────────────── -->
    <div class="map-topstrip">
      <div class="map-topstrip-inner">
        <div>
          <h2 class="map-title">Map <span class="text-orange">{{ state.providerName }}</span> fields</h2>
          <p class="map-sub">Click a source field (left) and a canonical field (right) to build a rule.</p>
        </div>
        <div class="flex items-center gap-3">
          <span class="badge badge-gray">{{ state.parsedFields.length }} source fields</span>
          <span class="badge badge-orange">{{ state.pendingMappings.length }} rule{{ state.pendingMappings.length !== 1 ? 's' : '' }}</span>
          <button
            class="btn btn-orange"
            :disabled="state.pendingMappings.length === 0"
            @click="goTo(3)"
          >
            Verify &amp; Confirm
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <polyline points="9 18 15 12 9 6"/>
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- ── Main content ─────────────────────────────────────── -->
    <div class="map-content">

      <!-- Left: source fields -->
      <div class="field-panel">
        <div class="panel-head">
          <span>Source Fields</span>
          <span class="panel-sub">{{ state.providerName }}</span>
        </div>
        <div class="panel-search">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input
            v-model="sourceSearch"
            type="text"
            placeholder="Search fields…"
            class="panel-search-input"
          />
          <button v-if="sourceSearch" class="search-clear" @click="sourceSearch = ''">×</button>
        </div>
        <div class="panel-body">
          <template v-if="state.parsedFields.length">
            <div
              v-for="f in filteredSourceFields"
              :key="f.path"
              class="field-row"
              :class="{
                selected:     state.selectedSource === f.path,
                'has-maps':   (sourceMapCount[f.path] || 0) > 0,
              }"
              @click="toggleSource(f.path)"
              :title="sourceMapCount[f.path] ? sourceTip(f.path) : ''"
            >
              <span class="field-dot"></span>
              <span class="field-path">{{ f.path }}</span>
              <span class="field-right">
                <span class="field-type">{{ f.type }}</span>
                <span v-if="sourceMapCount[f.path]" class="badge-count">×{{ sourceMapCount[f.path] }}</span>
              </span>
            </div>
            <div v-if="filteredSourceFields.length === 0" class="panel-empty">
              No fields match "<em>{{ sourceSearch }}</em>"
            </div>
          </template>
          <div v-else class="panel-empty">No fields parsed yet.</div>
        </div>
      </div>

      <!-- Middle column: builder -->
      <div class="builder-col">

        <!-- Arrow indicator -->
        <div class="builder-arrow">
          <div class="arrow-line"></div>
          <svg class="arrow-head" width="22" height="22" viewBox="0 0 24 24" fill="none"
            stroke="var(--o)" stroke-width="2" :opacity="bothSelected ? 1 : .25">
            <line x1="5" y1="12" x2="19" y2="12"/>
            <polyline points="12 5 19 12 12 19"/>
          </svg>
        </div>

        <!-- Builder card -->
        <div class="builder-card">
          <div class="builder-card-title">Rule builder</div>

          <!-- Static toggle -->
          <label class="toggle-row">
            <span>Static value</span>
            <div class="toggle-switch" :class="{ active: state.staticMode }" @click="state.staticMode = !state.staticMode">
              <div class="toggle-knob"></div>
            </div>
          </label>

          <!-- Source or static value display -->
          <div class="builder-chip" :class="{ empty: !sourceDisplay }">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
            </svg>
            {{ sourceDisplay || (state.staticMode ? 'Enter static value ↓' : 'Select source field ←') }}
          </div>
          <input
            v-if="state.staticMode"
            type="text"
            v-model="state.staticValue"
            placeholder='e.g. EXPRESS, DZD, 1.0'
            style="margin-bottom:10px"
          />

          <!-- Canonical display -->
          <div class="builder-chip canonical" :class="{ empty: !state.selectedCanonical }">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"/><path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/>
            </svg>
            {{ state.selectedCanonical || 'Select canonical field →' }}
          </div>

          <!-- Transform -->
          <label class="field-label" style="margin-top:6px">Transform (optional)</label>
          <select v-model="state.transform">
            <option value="">None</option>
            <option v-for="t in TRANSFORM_TYPES" :key="t" :value="t">{{ t }}</option>
          </select>

          <!-- Enum section (only for status) -->
          <div v-if="state.selectedCanonical === 'status'" class="enum-section">
            <div class="enum-head">Status enum mapping</div>
            <div class="enum-row">
              <input type="text" v-model="enumRaw" placeholder="source value (e.g. assigned)" />
              <span class="enum-arrow">→</span>
              <select v-model="enumTarget">
                <option v-for="sv in STATUS_VALUES" :key="sv">{{ sv }}</option>
              </select>
              <button class="btn btn-ghost btn-sm" @click="addEnum">+</button>
            </div>
            <div class="enum-tags">
              <span v-for="(v, k) in state.enumMappings" :key="k" class="enum-tag">
                {{ k }} → {{ v }}
                <button @click="delete state.enumMappings[k]">×</button>
              </span>
              <span v-if="!Object.keys(state.enumMappings).length" class="text-muted">No enum rules yet.</span>
            </div>
          </div>

          <!-- Add button -->
          <button
            class="btn btn-orange"
            style="width:100%;margin-top:14px"
            :disabled="!canAdd"
            @click="doAddMapping"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            Add Rule
          </button>
        </div>

        <!-- Back -->
        <button class="btn btn-ghost btn-sm" style="width:100%;margin-top:8px" @click="goTo(1)">
          ← Back
        </button>
      </div>

      <!-- Right: canonical fields -->
      <div class="field-panel">
        <div class="panel-head">
          <span>Canonical Fields</span>
          <span class="panel-sub">Platform model</span>
        </div>
        <div class="panel-body">
          <template v-for="(group, section) in groupedCanonical" :key="section">
            <div class="section-label">{{ section }}</div>
            <div
              v-for="cf in group"
              :key="cf.path"
              class="field-row"
              :class="{
                selected: state.selectedCanonical === cf.path,
                mapped:   canonicalMappedSet.has(cf.path),
              }"
              @click="toggleCanonical(cf.path)"
            >
              <span class="field-dot"></span>
              <span class="field-path">{{ cf.path }}</span>
              <span class="field-right">
                <span v-if="cf.isStatus" class="field-type">enum</span>
                <span v-if="canonicalMappedSet.has(cf.path)" class="badge badge-green" style="font-size:9px">✓</span>
              </span>
            </div>
          </template>
        </div>
      </div>

    </div>

    <!-- ── Pending rules bar ─────────────────────────────────── -->
    <div v-if="state.pendingMappings.length" class="pending-bar">
      <div class="pending-bar-inner">
        <span class="pending-label">Pending rules:</span>
        <div class="pending-chips">
          <div
            v-for="m in state.pendingMappings"
            :key="m.id"
            class="pending-chip"
          >
            <span class="chip-src">{{ m.staticValue ? `"${m.staticValue}"` : m.sourceField }}</span>
            <span class="chip-arrow">→</span>
            <span class="chip-can">{{ m.canonicalField }}</span>
            <span v-if="m.transformationType" class="badge badge-yellow" style="font-size:9px">{{ m.transformationType }}</span>
            <button class="chip-del" @click="removeMapping(m.id)">×</button>
          </div>
        </div>
        <button class="btn btn-orange btn-sm" @click="goTo(3)">
          Verify {{ state.pendingMappings.length }} rules →
        </button>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useMapping }    from '../composables/useMapping.js'
import { CANONICAL_FIELDS, TRANSFORM_TYPES, STATUS_VALUES } from '../data/canonical.js'

const { state, toast, goTo, addMapping, removeMapping, sourceMapCount, canonicalMappedSet } = useMapping()

// Source field search
const sourceSearch = ref('')
const filteredSourceFields = computed(() => {
  const q = sourceSearch.value.trim().toLowerCase()
  if (!q) return state.parsedFields
  return state.parsedFields.filter(f => f.path.toLowerCase().includes(q))
})

// Group canonical fields by section
const groupedCanonical = computed(() => {
  const g = {}
  for (const cf of CANONICAL_FIELDS) {
    if (!g[cf.section]) g[cf.section] = []
    g[cf.section].push(cf)
  }
  return g
})

const bothSelected = computed(() =>
  (state.selectedSource || state.staticMode) && state.selectedCanonical
)

const sourceDisplay = computed(() => {
  if (state.staticMode) return state.staticValue ? `"${state.staticValue}"` : ''
  return state.selectedSource || ''
})

const canAdd = computed(() => {
  if (!state.selectedCanonical) return false
  if (state.staticMode) return !!state.staticValue.trim()
  return !!state.selectedSource
})

function toggleSource(path) {
  state.selectedSource = state.selectedSource === path ? null : path
  if (state.selectedSource) state.staticMode = false
}

function toggleCanonical(path) {
  state.selectedCanonical = state.selectedCanonical === path ? null : path
  if (state.selectedCanonical !== 'status') state.enumMappings = {}
}

function sourceTip(path) {
  const targets = state.pendingMappings
    .filter(m => m.sourceField === path)
    .map(m => m.canonicalField)
  return `Mapped ×${targets.length} → ${targets.join(', ')}`
}

// Enum helpers
const enumRaw    = ref('')
const enumTarget = ref('READY')
function addEnum() {
  if (!enumRaw.value.trim()) return
  state.enumMappings[enumRaw.value.trim()] = enumTarget.value
  enumRaw.value = ''
}

function doAddMapping() {
  const ok = addMapping()
  if (ok) toast('Rule added!', 'success')
}
</script>

<style scoped>
.mapping-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

/* Top strip */
.map-topstrip {
  background: var(--s2);
  border-bottom: 1px solid var(--bd);
  flex-shrink: 0;
}
.map-topstrip-inner {
  max-width: 1440px;
  margin: 0 auto;
  padding: 14px 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.map-title {
  font-family: var(--f-head);
  font-size: 15px;
  font-weight: 800;
  color: var(--tx);
  letter-spacing: -.2px;
}
.map-sub { font-size: 12px; color: var(--tx-muted); margin-top: 2px; }

/* Three-column layout */
.map-content {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 220px 1fr;
  overflow: hidden;
  min-height: 0;
}

/* Field panels */
.field-panel {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--bd);
  overflow: hidden;
  min-height: 0;
}
.field-panel:last-child { border-right: none; border-left: 1px solid var(--bd); }

.panel-head {
  flex-shrink: 0;
  padding: 11px 16px;
  background: var(--s2);
  border-bottom: 1px solid var(--bd);
  font-family: var(--f-head);
  font-size: 11px;
  font-weight: 700;
  color: var(--tx-muted);
  text-transform: uppercase;
  letter-spacing: .5px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.panel-sub { font-size: 10px; color: var(--o); font-weight: 600; }

.panel-search {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  background: var(--s1);
  border-bottom: 1px solid var(--bd);
  color: var(--tx-muted);
}
.panel-search-input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  font-size: 12px;
  font-family: var(--f-body);
  color: var(--tx);
  padding: 0;
}
.panel-search-input::placeholder { color: var(--tx-faint); }
.search-clear {
  background: none;
  border: none;
  color: var(--tx-muted);
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
  transition: color .15s;
}
.search-clear:hover { color: var(--o); }

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  min-height: 0;
}
.panel-empty {
  padding: 40px 16px;
  text-align: center;
  color: var(--tx-muted);
  font-size: 13px;
}

.section-label {
  padding: 8px 10px 3px;
  font-size: 9px;
  font-weight: 800;
  color: var(--tx-faint);
  text-transform: uppercase;
  letter-spacing: .8px;
  font-family: var(--f-head);
}

/* Field rows */
.field-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background .1s;
  margin-bottom: 1px;
}
.field-row:hover { background: var(--s2); }
.field-row.selected { background: var(--o-d); }
.field-row.has-maps { background: rgba(249,115,22,.06); }
.field-row.has-maps:hover { background: rgba(249,115,22,.12); }
.field-row.mapped { background: var(--green-d); }
.field-row.mapped:hover { background: rgba(34,197,94,.18); }

.field-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--bd-hi);
  flex-shrink: 0;
  transition: background .15s;
}
.field-row.selected .field-dot, .field-row.has-maps .field-dot { background: var(--o); }
.field-row.mapped    .field-dot { background: var(--green); }

.field-path {
  font-family: var(--f-mono);
  font-size: 11.5px;
  color: var(--tx);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.field-row.selected .field-path { color: var(--o); font-weight: 500; }

.field-right {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}
.field-type {
  font-size: 10px;
  color: var(--tx-muted);
  font-family: var(--f-head);
}

/* Builder column */
.builder-col {
  padding: 16px 12px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  border-right: 1px solid var(--bd);
}

.builder-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px 0 8px;
}
.arrow-line { display: none; }
.arrow-head { transition: opacity .2s; }

.builder-card {
  background: var(--s2);
  border: 1px solid var(--bd);
  border-radius: var(--r);
  padding: 16px;
}
.builder-card-title {
  font-family: var(--f-head);
  font-size: 10px;
  font-weight: 800;
  color: var(--tx-muted);
  text-transform: uppercase;
  letter-spacing: .8px;
  margin-bottom: 12px;
}

.toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  font-family: var(--f-head);
  color: var(--tx-muted);
  margin-bottom: 10px;
  cursor: pointer;
  user-select: none;
}
.toggle-switch {
  width: 34px;
  height: 19px;
  background: var(--s3);
  border: 1px solid var(--bd-hi);
  border-radius: 99px;
  position: relative;
  transition: background .2s, border-color .2s;
  cursor: pointer;
}
.toggle-switch.active { background: var(--o); border-color: var(--o); }
.toggle-knob {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: var(--tx-muted);
  position: absolute;
  top: 2px;
  left: 2px;
  transition: transform .2s, background .2s;
}
.toggle-switch.active .toggle-knob { transform: translateX(15px); background: #000; }

.builder-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 11px;
  border-radius: var(--r-sm);
  font-family: var(--f-mono);
  font-size: 11.5px;
  color: var(--o);
  background: var(--o-d);
  border: 1px solid rgba(249,115,22,.2);
  margin-bottom: 8px;
  min-height: 34px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.builder-chip.canonical { color: var(--green); background: var(--green-d); border-color: rgba(34,197,94,.2); }
.builder-chip.empty {
  color: var(--tx-muted);
  background: var(--s1);
  border-color: var(--bd);
  font-family: var(--f-body);
  font-style: italic;
  font-size: 11px;
}

/* Enum section */
.enum-section {
  margin-top: 10px;
  background: var(--s1);
  border: 1px solid var(--bd);
  border-radius: var(--r-sm);
  padding: 12px;
}
.enum-head {
  font-size: 10px;
  font-weight: 700;
  color: var(--tx-muted);
  text-transform: uppercase;
  letter-spacing: .5px;
  font-family: var(--f-head);
  margin-bottom: 8px;
}
.enum-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.enum-row input, .enum-row select { flex: 1; padding: 6px 8px; font-size: 11px; }
.enum-arrow { color: var(--o); font-weight: 700; flex-shrink: 0; }
.enum-tags { display: flex; flex-wrap: wrap; gap: 5px; }
.enum-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: var(--f-mono);
  font-size: 11px;
  color: var(--o);
  background: var(--o-d);
  border: 1px solid rgba(249,115,22,.2);
  border-radius: 99px;
  padding: 2px 9px;
}
.enum-tag button {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--tx-muted);
  font-size: 13px;
  line-height: 1;
}
.enum-tag button:hover { color: var(--red); }

/* Pending bar */
.pending-bar {
  flex-shrink: 0;
  background: var(--s2);
  border-top: 1px solid var(--bd);
}
.pending-bar-inner {
  max-width: 1440px;
  margin: 0 auto;
  padding: 10px 28px;
  display: flex;
  align-items: center;
  gap: 12px;
  overflow: hidden;
}
.pending-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--tx-muted);
  font-family: var(--f-head);
  text-transform: uppercase;
  letter-spacing: .5px;
  flex-shrink: 0;
}
.pending-chips {
  display: flex;
  gap: 7px;
  overflow-x: auto;
  flex: 1;
  padding-bottom: 2px;
}
.pending-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  background: var(--s3);
  border: 1px solid var(--bd-hi);
  border-radius: 7px;
  padding: 4px 10px;
  flex-shrink: 0;
  font-size: 11px;
}
.chip-src { font-family: var(--f-mono); color: var(--o); font-size: 11px; }
.chip-arrow { color: var(--tx-muted); }
.chip-can { font-family: var(--f-mono); color: var(--green); font-size: 11px; }
.chip-del {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--tx-muted);
  font-size: 14px;
  line-height: 1;
  padding: 0 0 0 3px;
}
.chip-del:hover { color: var(--red); }
</style>
