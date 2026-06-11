#!/usr/bin/env node
/**
 * Codemod (pass 2) — snap remaining OFF-SCALE text-[Npx] to the nearest scale token.
 * Differences are ≤1.5px (imperceptible for text). Run after codemod-typo.mjs.
 *
 * Scale: 2xs=10, xs=11, sm=12, base=13, md=14, lg=15, xl=18, 2xl=22, 3xl=28.
 *   8/9/9.5/10.5 -> 2xs(10) · 11.5 -> xs(11) · 12.5 -> sm(12) · 13.5 -> base(13)
 *   16/17 -> xl(18) · 20 -> 2xl(22) · 28 -> 3xl(28, exact)
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
const DRY = process.argv.includes('--dry');

const MAP = {
  '8px': '2xs', '9px': '2xs', '9.5px': '2xs', '10.5px': '2xs',
  '11.5px': 'xs', '12.5px': 'sm', '13.5px': 'base',
  '16px': 'xl', '17px': 'xl', '20px': '2xl', '28px': '3xl',
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
for (const file of walk(ROOT)) {
  const src = readFileSync(file, 'utf8');
  const out = src.replace(/(\b(?:[a-z-]+:)*)text-\[([0-9.]+px)\]/g, (m, prefix, px) => {
    const token = MAP[px];
    if (!token) return m;
    replaced++;
    return `${prefix}text-${token}`;
  });
  if (out !== src) { filesChanged++; if (!DRY) writeFileSync(file, out, 'utf8'); }
}
console.log(`${DRY ? '[dry-run] ' : ''}Snapped ${replaced} off-scale text-[Npx] -> nearest token, across ${filesChanged} files.`);
