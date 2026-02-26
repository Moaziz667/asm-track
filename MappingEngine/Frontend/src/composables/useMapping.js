import { reactive, ref, computed } from 'vue'

const API = '/api/mappings'

// ── Singleton state ────────────────────────────────────────
const state = reactive({
  step:      1,
  direction: 1,    // 1 = forward, -1 = backward

  // Step 1
  providerName: '',
  rawJson:      '',
  parsedFields: [],  // [{ path, type }]

  // Step 2
  selectedSource:   null,
  selectedCanonical: null,
  transform:    '',
  staticMode:   false,
  staticValue:  '',
  enumMappings: {}, // { rawVal: canonicalVal }
  pendingMappings: [], // local rules not yet persisted

  // Step 4
  saving:     false,
  saveResults: [],
})

const toasts = ref([])

// ── Toast ──────────────────────────────────────────────────
let _toastId = 0
function toast(msg, type = 'info', ms = 3800) {
  const id = ++_toastId
  toasts.value.push({ id, msg, type })
  setTimeout(() => {
    const i = toasts.value.findIndex(t => t.id === id)
    if (i > -1) toasts.value.splice(i, 1)
  }, ms)
}

// ── Navigation ─────────────────────────────────────────────
function goTo(step) {
  state.direction = step > state.step ? 1 : -1
  state.step = step
}

// ── JSON parsing ───────────────────────────────────────────
function parseFields(obj, prefix = '', out = []) {
  if (obj === null || obj === undefined) return out
  if (Array.isArray(obj)) {
    if (obj.length > 0) parseFields(obj[0], `${prefix}[0]`, out)
    return out
  }
  if (typeof obj === 'object') {
    for (const key of Object.keys(obj)) {
      const full = prefix ? `${prefix}.${key}` : key
      const val  = obj[key]
      if (val !== null && typeof val === 'object') parseFields(val, full, out)
      else out.push({ path: full, type: val === null ? 'null' : typeof val })
    }
    return out
  }
  if (prefix) out.push({ path: prefix, type: typeof obj })
  return out
}

function tryParseJson(raw) {
  try { return JSON.parse(raw) }
  catch { return null }
}

// ── Source map count ───────────────────────────────────────
const sourceMapCount = computed(() => {
  const c = {}
  state.pendingMappings.filter(m => m.sourceField).forEach(m => {
    c[m.sourceField] = (c[m.sourceField] || 0) + 1
  })
  return c
})

const canonicalMappedSet = computed(() =>
  new Set(state.pendingMappings.map(m => m.canonicalField))
)

// ── Add mapping ────────────────────────────────────────────
function addMapping(opts = {}) {
  const src       = opts.sourceField    ?? state.selectedSource
  const canonical = opts.canonicalField ?? state.selectedCanonical
  const transform = opts.transform      ?? state.transform
  const isStatic  = opts.staticMode     ?? state.staticMode
  const sv        = opts.staticValue    ?? state.staticValue
  const em        = opts.enumMappings   ?? { ...state.enumMappings }

  if (!canonical) { toast('Select a canonical field first.', 'error'); return false }
  if (!isStatic && !src) { toast('Select a source field or enable static mode.', 'error'); return false }
  if (isStatic && !sv)   { toast('Enter a static value.', 'error'); return false }

  const dup = state.pendingMappings.find(
    m => m.canonicalField === canonical &&
         (isStatic ? m.staticValue === sv : m.sourceField === src)
  )
  if (dup) { toast('This exact mapping already exists.', 'error'); return false }

  state.pendingMappings.push({
    id:                 Date.now() + Math.random(),
    sourceField:        isStatic ? null : src,
    canonicalField:     canonical,
    transformationType: transform || null,
    staticValue:        isStatic ? sv : null,
    enumMapping:        Object.keys(em).length ? em : null,
    name: isStatic
      ? `static "${sv}" → ${canonical}`
      : `${src} → ${canonical}`,
  })

  // Reset selection
  state.selectedSource    = null
  state.selectedCanonical = null
  state.transform         = ''
  state.staticMode        = false
  state.staticValue       = ''
  state.enumMappings      = {}

  return true
}

function removeMapping(id) {
  const i = state.pendingMappings.findIndex(m => m.id === id)
  if (i > -1) state.pendingMappings.splice(i, 1)
}

// ── Save all to backend ────────────────────────────────────
async function saveAll() {
  state.saving = true
  state.saveResults = []

  for (const m of state.pendingMappings) {
    try {
      const res = await fetch(API, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name:               m.name,
          sourceField:        m.sourceField,
          canonicalField:     m.canonicalField,
          transformationType: m.transformationType,
          staticValue:        m.staticValue,
          enumMapping:        m.enumMapping,
        }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      state.saveResults.push({ ok: true, mapping: m })
    } catch (e) {
      state.saveResults.push({ ok: false, mapping: m, error: e.message })
    }
  }

  state.saving = false
  return state.saveResults
}

// ── Reset for new session ──────────────────────────────────
function reset() {
  state.step            = 1
  state.direction       = 1
  state.providerName    = ''
  state.rawJson         = ''
  state.parsedFields    = []
  state.selectedSource  = null
  state.selectedCanonical = null
  state.transform       = ''
  state.staticMode      = false
  state.staticValue     = ''
  state.enumMappings    = {}
  state.pendingMappings = []
  state.saving          = false
  state.saveResults     = []
}

export function useMapping() {
  return {
    state,
    toasts,
    toast,
    goTo,
    parseFields,
    tryParseJson,
    sourceMapCount,
    canonicalMappedSet,
    addMapping,
    removeMapping,
    saveAll,
    reset,
  }
}
