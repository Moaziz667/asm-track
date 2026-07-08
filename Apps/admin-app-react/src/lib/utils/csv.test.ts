import { describe, it, expect } from 'vitest';
import { toCsv, type CsvColumn } from './csv';

type Row = { name: string; qty: number; note?: string | null };
const cols: CsvColumn<Row>[] = [
  { header: 'Name', accessor: r => r.name },
  { header: 'Qty', accessor: r => r.qty },
  { header: 'Note', accessor: r => r.note },
];

const BOM = '﻿';

describe('toCsv', () => {
  it('prepends a UTF-8 BOM and a header row', () => {
    const out = toCsv<Row>([], cols);
    expect(out.startsWith(BOM)).toBe(true);
    expect(out).toBe(`${BOM}Name,Qty,Note\r\n`);
  });

  it('joins rows with CRLF and renders cell values', () => {
    const out = toCsv<Row>([{ name: 'A', qty: 2, note: 'x' }, { name: 'B', qty: 3, note: 'y' }], cols);
    expect(out).toBe(`${BOM}Name,Qty,Note\r\nA,2,x\r\nB,3,y`);
  });

  it('escapes fields containing comma, quote, or newline (RFC 4180)', () => {
    const out = toCsv<Row>([
      { name: 'Doe, John', qty: 1, note: 'say "hi"' },
      { name: 'line1\nline2', qty: 2, note: null },
    ], cols);
    const lines = out.replace(BOM, '').split('\r\n');
    expect(lines[1]).toBe('"Doe, John",1,"say ""hi"""');
    expect(lines[2]).toBe('"line1\nline2",2,'); // null → empty field
  });

  it('renders nullish accessor results as empty strings', () => {
    const out = toCsv<Row>([{ name: 'A', qty: 0, note: undefined }], cols);
    expect(out.replace(BOM, '').split('\r\n')[1]).toBe('A,0,');
  });
});
