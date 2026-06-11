#!/usr/bin/env node
/**
 * Codemod — migrate arbitrary rounded-[Npx] to the borderRadius scale (tailwind.config.ts).
 *
 * Maps each arbitrary px to the nearest EXACT scale token so the rendered radius is identical:
 *   2px->xs, 4px->sm, 6px->md, 8px->lg, 12px->xl, 16px->2xl.
 * Near-neighbours with no exact token (1px, 3px, 10px, 14px) snap to the closest token — radius
 * differences of ≤2px are visually imperceptible (unlike font sizes). Reported for transparency.
 *
 *   node scripts/codemod-radius.mjs --dry   # preview
 *   node scripts/codemod-radius.mjs         # apply
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
const DRY = process.argv.includes('--dry');

// px -> token. Exact scale: 2,4,6,8,12,16. Near values snap to closest (≤2px diff, imperceptible).
const MAP = {
  '1px': 'xs', '2px': 'xs', '3px': 'xs',        // ~2px
  '4px': 'sm', '5px': 'sm',                      // 4px
  '6px': 'md', '7px': 'md',                      // 6px
  '8px': 'lg', '9px': 'lg', '10px': 'lg',        // 8px
  '11px': 'xl', '12px': 'xl', '13px': 'xl', '14px': 'xl', // 12px
  '15px': '2xl', '16px': '2xl', '17px': '2xl', '18px': '2xl', // 16px
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
const snapped = {};
for (const file of walk(ROOT)) {
  const src = readFileSync(file, 'utf8');
  const out = src.replace(/(\b(?:[a-z-]+:)*)rounded-\[([0-9.]+px)\]/g, (m, prefix, px) => {
    const token = MAP[px];
    if (!token) return m;
    replaced++;
    if (!['2px', '4px', '6px', '8px', '12px', '16px'].includes(px)) snapped[px] = (snapped[px] || 0) + 1;
    return `${prefix}rounded-${token}`;
  });
  if (out !== src) { filesChanged++; if (!DRY) writeFileSync(file, out, 'utf8'); }
}

console.log(`${DRY ? '[dry-run] ' : ''}Replaced ${replaced} rounded-[Npx] -> scale tokens, across ${filesChanged} files.`);
if (Object.keys(snapped).length) {
  console.log('\nNear-values snapped to closest token (≤2px, imperceptible):');
  for (const [px, n] of Object.entries(snapped).sort((a, b) => b[1] - a[1])) console.log(`  ${String(n).padStart(3)}  rounded-[${px}]`);
}
