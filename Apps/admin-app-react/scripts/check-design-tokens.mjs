#!/usr/bin/env node
/**
 * Design-system guardrail (targeted, zero false positives).
 *
 * Flags ONLY the real anti-patterns that bypass the token scale:
 *   - arbitrary typography sizes:  text-[14px]
 *   - hardcoded hex colors in classNames:  bg-[#fff], text-[#C7372F], border-[#abc]
 *
 * It deliberately does NOT flag legitimate arbitrary values:
 *   - CSS variables / tokens:  bg-[var(--surface)], text-[var(--text-primary)]
 *   - computed values:  min-h-[calc(100vh-56px)], max-w-[1800px] layout one-offs
 *
 * Usage:
 *   node scripts/check-design-tokens.mjs            # report counts, exit 0
 *   node scripts/check-design-tokens.mjs --max 0    # fail (exit 1) if any violation — for CI/pre-commit once cleaned
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
const PATTERNS = [
  { name: 'arbitrary text size', re: /\btext-\[[0-9.]+px\]/g },
  { name: 'hardcoded hex color', re: /\b(?:bg|text|border|ring|fill|stroke|from|to|via)-\[#[0-9A-Fa-f]{3,8}\]/g },
];

const maxArg = process.argv.indexOf('--max');
const MAX = maxArg !== -1 ? Number(process.argv[maxArg + 1]) : Infinity;

function walk(dir) {
  let files = [];
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    const s = statSync(p);
    if (s.isDirectory()) files = files.concat(walk(p));
    else if (['.tsx', '.ts'].includes(extname(p))) files.push(p);
  }
  return files;
}

let total = 0;
const perFile = [];
for (const file of walk(ROOT)) {
  const src = readFileSync(file, 'utf8');
  let count = 0;
  for (const { re } of PATTERNS) count += (src.match(re) || []).length;
  if (count > 0) { perFile.push([file, count]); total += count; }
}

perFile.sort((a, b) => b[1] - a[1]);
console.log('Design-token violations (arbitrary text-[Npx] + hardcoded hex):\n');
for (const [file, count] of perFile.slice(0, 25)) {
  console.log(`  ${String(count).padStart(4)}  ${file.slice(ROOT.length - 3)}`);
}
console.log(`\n  TOTAL: ${total} violations across ${perFile.length} files`);

if (total > MAX) {
  console.error(`\n✗ ${total} violations exceed the allowed max (${MAX}).`);
  process.exit(1);
}
console.log(MAX === Infinity ? '\n(report only — pass --max N to enforce)' : `\n✓ within max ${MAX}`);
