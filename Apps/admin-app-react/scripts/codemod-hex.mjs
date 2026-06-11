#!/usr/bin/env node
/**
 * Codemod — replace hardcoded hex colors in classNames with semantic design tokens.
 *
 * Unlike the typo/radius codemods (pixel-identical), this HARMONIZES colors onto the token system:
 * the many slightly-different reds/greens/ambers collapse to --danger / --success / --warning / --info.
 * Small hue shifts are intended (one source of truth). Reviewed visually after.
 *
 *   node scripts/codemod-hex.mjs --dry   # preview
 *   node scripts/codemod-hex.mjs         # apply
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
const DRY = process.argv.includes('--dry');

// hex (lowercased) -> CSS token. Grouped by semantic family.
const HEX = {
  // danger / red
  '#ef4444': 'var(--danger)', '#dc2626': 'var(--danger)', '#c7372f': 'var(--danger)',
  '#a52b24': 'var(--danger)', '#b91c1c': 'var(--danger)', '#f87171': 'var(--danger)',
  // danger soft backgrounds / borders
  '#fef2f2': 'var(--danger-bg)', '#fff5f3': 'var(--danger-bg)', '#fecaca': 'var(--danger)',
  // success / green
  '#2d8a5e': 'var(--success)', '#4caf82': 'var(--success)', '#16a34a': 'var(--success)',
  '#22c55e': 'var(--success)', '#059669': 'var(--success)', '#10b981': 'var(--success)',
  '#a7f3d0': 'var(--success)',
  // warning / amber
  '#b45309': 'var(--warning)', '#a16207': 'var(--warning)', '#d97706': 'var(--warning)',
  '#f59e0b': 'var(--warning)', '#c4881a': 'var(--warning)', '#a06d10': 'var(--warning)',
  '#fffbeb': 'var(--warning-bg)', '#fef08a': 'var(--warning)', '#fde68a': 'var(--warning)',
  // info / blue/cyan
  '#3b82f6': 'var(--info)', '#3e6ae1': 'var(--brand)', '#0891b2': 'var(--info)',
  '#2594b8': 'var(--info)', '#1a7a9a': 'var(--info)', '#5e6ad2': 'var(--brand)',
  // neutral greys
  '#e4e4e7': 'var(--border)', '#8a8f98': 'var(--text-soft)', '#6b7280': 'var(--text-muted)',
  '#f4f4f5': 'var(--hover-bg)', '#faf7f2': 'var(--hover-bg)',
  // extra success greens / soft bg
  '#ecfdf5': 'var(--success-bg)', '#d1fae5': 'var(--success-bg)', '#065f46': 'var(--success)',
  '#047857': 'var(--success)', '#15803d': 'var(--success)',
  // extra info blues / cyan
  '#eff6ff': 'var(--info-bg)', '#2563eb': 'var(--info)', '#06b6d4': 'var(--info)',
  // extra warning soft bg
  '#fefce8': 'var(--warning-bg)',
};

function walk(dir) {
  let files = [];
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    statSync(p).isDirectory() ? (files = files.concat(walk(p))) : (['.tsx', '.ts'].includes(extname(p)) && files.push(p));
  }
  return files;
}

let replaced = 0, filesChanged = 0;
const unmapped = {};
for (const file of walk(ROOT)) {
  const src = readFileSync(file, 'utf8');
  const out = src.replace(/\b(bg|text|border|ring|fill|stroke|from|to|via)-\[(#[0-9A-Fa-f]{3,6})\]/g, (m, util, hex) => {
    const token = HEX[hex.toLowerCase()];
    if (!token) { unmapped[hex.toLowerCase()] = (unmapped[hex.toLowerCase()] || 0) + 1; return m; }
    replaced++;
    return `${util}-[${token}]`;
  });
  if (out !== src) { filesChanged++; if (!DRY) writeFileSync(file, out, 'utf8'); }
}

console.log(`${DRY ? '[dry-run] ' : ''}Replaced ${replaced} hardcoded hex -> tokens, across ${filesChanged} files.`);
if (Object.keys(unmapped).length) {
  console.log('\nUNMAPPED hex left as-is (add to map if needed):');
  for (const [hex, n] of Object.entries(unmapped).sort((a, b) => b[1] - a[1])) console.log(`  ${String(n).padStart(3)}  ${hex}`);
}
