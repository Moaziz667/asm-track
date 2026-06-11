#!/usr/bin/env node
/**
 * Codemod — migrate arbitrary text-[Npx] to the Tailwind font-size scale (tailwind.config.ts).
 *
 * STRICT 1:1 mapping — only the px values that have an EXACT scale token are replaced.
 * Off-scale sizes (9px, 11.5px, 16px, 20px, 28px…) are LEFT UNTOUCHED and reported for manual
 * decision — never guessed. This keeps the visual result identical (same px) while removing the
 * arbitrary syntax.
 *
 *   node scripts/codemod-typo.mjs          # apply
 *   node scripts/codemod-typo.mjs --dry    # preview counts only, no writes
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
const DRY = process.argv.includes('--dry');

// Exact px -> scale token (from tailwind.config.ts fontSize). Only these are safe to replace.
const MAP = {
  '10px': '2xs', '11px': 'xs', '12px': 'sm', '13px': 'base',
  '14px': 'md', '15px': 'lg', '18px': 'xl', '22px': '2xl',
};

function walk(dir) {
  let files = [];
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    statSync(p).isDirectory() ? (files = files.concat(walk(p))) : (['.tsx', '.ts'].includes(extname(p)) && files.push(p));
  }
  return files;
}

let replaced = 0;
const offScale = {};
let filesChanged = 0;

for (const file of walk(ROOT)) {
  const src = readFileSync(file, 'utf8');
  let out = src;
  // Replace only text-[Npx] where N is an exact scale value. Handle prefixes (hover:, dark:, sm:…)
  out = out.replace(/(\b(?:[a-z-]+:)*)text-\[([0-9.]+px)\]/g, (m, prefix, px) => {
    const token = MAP[px];
    if (token) { replaced++; return `${prefix}text-${token}`; }
    offScale[px] = (offScale[px] || 0) + 1; // off-scale: leave as-is
    return m;
  });
  if (out !== src) {
    filesChanged++;
    if (!DRY) writeFileSync(file, out, 'utf8');
  }
}

console.log(`${DRY ? '[dry-run] ' : ''}Replaced ${replaced} arbitrary text-[Npx] -> scale tokens, across ${filesChanged} files.\n`);
console.log('Off-scale sizes LEFT UNTOUCHED (need manual decision):');
for (const [px, n] of Object.entries(offScale).sort((a, b) => b[1] - a[1])) {
  console.log(`  ${String(n).padStart(4)}  text-[${px}]`);
}
