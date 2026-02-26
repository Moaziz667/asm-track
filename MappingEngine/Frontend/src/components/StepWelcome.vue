<template>
  <div class="welcome-page">

    <!-- Bauhaus geometric background decoration -->
    <div class="bg-geo" aria-hidden="true">
      <div class="geo-circle geo-c1"></div>
      <div class="geo-circle geo-c2"></div>
      <div class="geo-line geo-l1"></div>
      <div class="geo-line geo-l2"></div>
      <div class="geo-dot geo-d1"></div>
      <div class="geo-dot geo-d2"></div>
      <div class="geo-dot geo-d3"></div>
    </div>

    <div class="welcome-inner">

      <!-- ── Hero section ──────────────────────────────────── -->
      <div class="hero">
        <div class="hero-eyebrow">
          <span class="eyebrow-pill">● Integration Wizard</span>
        </div>
        <h1 class="hero-title">
          Map your data<br /><span class="title-accent">to the platform.</span>
        </h1>
        <p class="hero-desc">
          Define how your provider's JSON fields translate into our canonical delivery model.
          One wizard. Zero surprises.
        </p>

        <!-- Mini stats chips -->
        <div class="hero-stats">
          <div class="stat-chip"><span class="stat-n">29</span> canonical fields</div>
          <div class="stat-chip"><span class="stat-n">6</span> sections</div>
          <div class="stat-chip"><span class="stat-n">6</span> transforms</div>
        </div>
      </div>

      <!-- ── Form card ─────────────────────────────────────── -->
      <div class="form-card">

        <!-- Card header accent bar -->
        <div class="form-card-accent"></div>

        <div class="form-card-body">

          <h2 class="form-card-title">Start your integration</h2>
          <p class="form-card-sub">Tell us who you're connecting and paste a sample payload.</p>

          <!-- Provider name -->
          <div class="form-group">
            <label class="field-label" for="provider-name">
              Provider / Source Name
            </label>
            <input
              id="provider-name"
              type="text"
              v-model="state.providerName"
              placeholder="e.g. Odoo, Yalidine, Shopify…"
              @keyup.enter="focusJson"
              autocomplete="off"
            />
          </div>

          <!-- JSON payload -->
          <div class="form-group">
            <div class="label-row">
              <label class="field-label" for="json-input">Sample JSON Payload</label>
              <label class="upload-btn">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                  <polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
                </svg>
                Browse
                <input type="file" accept=".json,application/json" @change="handleFile" />
              </label>
            </div>
            <textarea
              id="json-input"
              ref="jsonRef"
              v-model="state.rawJson"
              rows="12"
              placeholder='{
  "order": {
    "ref": "ORD-2026-001",
    "status": "assigned",
    "customer": { "name": "Sarah M.", "phone": "+213600123456" },
    "destination": { "address": "12 Rue des Lilas, Alger", "city": "Alger" },
    "payment": { "total": 5800, "currency": "DZD", "type": "COD" }
  }
}'
            ></textarea>
          </div>

          <!-- Error -->
          <div v-if="jsonError" class="json-error">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
            </svg>
            {{ jsonError }}
          </div>

          <!-- Actions -->
          <div class="form-actions">
            <button
              class="btn btn-orange btn-xl"
              :disabled="!canProceed || parsing"
              @click="proceed"
            >
              <span v-if="parsing">Parsing…</span>
              <template v-else>
                Parse &amp; Continue
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <polyline points="9 18 15 12 9 6"/>
                </svg>
              </template>
            </button>
          </div>

        </div>
      </div>

    </div>
  </div>
</template>

<script setup>
import { ref, computed }   from 'vue'
import { useMapping }      from '../composables/useMapping.js'

const { state, toast, goTo, parseFields, tryParseJson } = useMapping()

const jsonRef  = ref(null)
const jsonError = ref('')
const parsing   = ref(false)

const canProceed = computed(() =>
  state.providerName.trim().length > 0 &&
  state.rawJson.trim().length > 0
)

function focusJson() {
  jsonRef.value?.focus()
}

function handleFile(e) {
  const file = e.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = ev => {
    state.rawJson = ev.target.result
    jsonError.value = ''
  }
  reader.readAsText(file)
}

async function proceed() {
  if (!canProceed.value) return
  jsonError.value = ''

  const parsed = tryParseJson(state.rawJson.trim())
  if (!parsed) {
    jsonError.value = 'Invalid JSON — check the syntax and try again.'
    return
  }

  parsing.value = true

  // Try backend parser, fallback to local
  let fields = []
  try {
    const res = await fetch('/api/mappings/parse-json', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ payload: parsed }),
    })
    if (res.ok) {
      const data = await res.json()
      fields = data.paths.map(p => ({ path: p, type: 'auto' }))
    } else {
      throw new Error('')
    }
  } catch {
    fields = parseFields(parsed)
  }

  state.parsedFields = fields
  parsing.value = false

  toast(`Found ${fields.length} fields in "${state.providerName}" payload.`, 'success')
  goTo(2)
}
</script>

<style scoped>
/* ── Page shell ─────────────────────────────────────────── */
.welcome-page {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  position: relative;
  overflow: hidden;
}

/* ── Bauhaus background geometry ────────────────────────── */
.bg-geo { position: absolute; inset: 0; pointer-events: none; overflow: hidden; }
.geo-circle {
  position: absolute;
  border-radius: 50%;
  border: 1px solid rgba(249,115,22,.07);
}
.geo-c1 { width: 600px; height: 600px; top: -200px; right: -180px; }
.geo-c2 { width: 280px; height: 280px; bottom: -60px; left: 60px;
          border-color: rgba(249,115,22,.05); }
.geo-line {
  position: absolute;
  background: rgba(249,115,22,.06);
}
.geo-l1 { width: 1px; height: 100%; top: 0; left: 38%; }
.geo-l2 { width: 100%; height: 1px; top: 55%; left: 0; }
.geo-dot {
  position: absolute;
  border-radius: 50%;
  background: var(--o);
}
.geo-d1 { width: 6px; height: 6px; top: 82px; left: 38%; opacity: .6; }
.geo-d2 { width: 4px; height: 4px; top: 55%; left: 18%; opacity: .4; }
.geo-d3 { width: 8px; height: 8px; top: 55%; right: 22%; opacity: .3; }

/* ── Inner layout ───────────────────────────────────────── */
.welcome-inner {
  display: grid;
  grid-template-columns: 1fr 520px;
  gap: 64px;
  width: 100%;
  max-width: 1080px;
  align-items: center;
  position: relative;
  z-index: 1;
}

/* ── Hero ───────────────────────────────────────────────── */
.eyebrow-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--f-head);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .8px;
  text-transform: uppercase;
  color: var(--o);
  background: rgba(249,115,22,.1);
  border: 1px solid rgba(249,115,22,.2);
  padding: 5px 12px;
  border-radius: 99px;
  margin-bottom: 24px;
}
.hero-title {
  font-family: var(--f-head);
  font-size: clamp(36px, 5vw, 56px);
  font-weight: 800;
  color: var(--tx);
  line-height: 1.1;
  letter-spacing: -1.5px;
  margin-bottom: 20px;
}
.title-accent { color: var(--o); }
.hero-desc {
  font-size: 15px;
  color: var(--tx-muted);
  line-height: 1.7;
  max-width: 420px;
  margin-bottom: 32px;
}
.hero-stats {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.stat-chip {
  background: var(--s2);
  border: 1px solid var(--bd);
  border-radius: var(--r-sm);
  padding: 7px 14px;
  font-size: 12px;
  font-family: var(--f-head);
  font-weight: 600;
  color: var(--tx-muted);
  display: flex;
  align-items: center;
  gap: 6px;
}
.stat-n {
  color: var(--o);
  font-weight: 800;
  font-size: 14px;
}

/* ── Form card ──────────────────────────────────────────── */
.form-card {
  background: var(--s1);
  border: 1px solid var(--bd);
  border-radius: var(--r-lg);
  box-shadow: 0 24px 64px rgba(0,0,0,.6);
  overflow: hidden;
}
.form-card-accent {
  height: 3px;
  background: linear-gradient(90deg, var(--o), var(--o-h), rgba(249,115,22,.2));
}
.form-card-body { padding: 32px; }
.form-card-title {
  font-family: var(--f-head);
  font-size: 20px;
  font-weight: 800;
  color: var(--tx);
  letter-spacing: -.4px;
  margin-bottom: 6px;
}
.form-card-sub {
  font-size: 13px;
  color: var(--tx-muted);
  margin-bottom: 28px;
  line-height: 1.5;
}
.form-group { margin-bottom: 20px; }

.label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
}
.label-row .field-label { margin-bottom: 0; }

.upload-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 700;
  font-family: var(--f-head);
  color: var(--tx-muted);
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 5px;
  border: 1px solid var(--bd);
  background: var(--s2);
  transition: color .15s, border-color .15s;
  letter-spacing: .3px;
  text-transform: uppercase;
}
.upload-btn:hover { color: var(--o); border-color: rgba(249,115,22,.4); }
.upload-btn input { display: none; }

.json-error {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--red);
  font-size: 12px;
  font-weight: 500;
  background: var(--red-d);
  border: 1px solid rgba(239,68,68,.2);
  border-radius: var(--r-sm);
  padding: 9px 13px;
  margin-top: -8px;
  margin-bottom: 16px;
}

.form-actions { margin-top: 4px; }
.form-actions .btn { width: 100%; }
</style>
