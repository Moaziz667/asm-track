import { describe, expect, it } from 'vitest';
import { PRIMARY_MODEL, buildOptions, modelsFor, rank } from './mappingScope';
import type { ErpField } from '@/lib/api/erpIntegration';

const f = (name: string, label: string, custom = false): ErpField =>
  ({ name, label, type: 'char', custom });

const CATALOGUE: Record<string, ErpField[]> = {
  'stock.picking': [f('name', 'Reference'), f('origin', 'Source Document')],
  'sale.order': [f('name', 'Order Reference'), f('client_order_ref', 'Customer Reference')],
  'res.partner': [f('city', 'City'), f('x_client_nom', 'Nom client', true)],
  'stock.move': [f('quantity', 'Quantity'), f('x_lot_number', 'Lot', true)],
  'product.product': [f('default_code', 'Internal Reference')],
  'sale.order.line': [f('price_unit', 'Unit Price')],
};

describe('scope', () => {
  it('offers a line row the line models first, header models still reachable', () => {
    const models = modelsFor('LINE', CATALOGUE);
    expect(models[0]).toBe('stock.move');
    expect(models).toContain('sale.order');
    expect(models.indexOf('stock.move')).toBeLessThan(models.indexOf('stock.picking'));
  });

  it('never offers a header row a line model, which it could not read', () => {
    const models = modelsFor('HEADER', CATALOGUE);
    expect(models).not.toContain('stock.move');
    expect(models).not.toContain('sale.order.line');
    expect(models).not.toContain('product.product');
  });

  it('drops models this ERP did not report rather than showing an empty group', () => {
    expect(modelsFor('HEADER', { 'stock.picking': [f('name', 'Reference')] }))
      .toEqual(['stock.picking']);
  });
});

describe('path building', () => {
  // The rule the backend depends on: a bare line path is read against the stock move, so a line
  // field must be stored bare and a picking field must be qualified — the reverse of a header row.
  it('stores a line field bare and qualifies everything else', () => {
    const options = buildOptions(modelsFor('LINE', CATALOGUE), CATALOGUE, PRIMARY_MODEL.LINE);
    const path = (name: string) => options.find((o) => o.name === name)?.path;

    expect(path('x_lot_number')).toBe('x_lot_number');
    expect(path('origin')).toBe('stock.picking:origin');
    expect(path('city')).toBe('res.partner:city');
  });

  it('stores a header field bare against the picking', () => {
    const options = buildOptions(modelsFor('HEADER', CATALOGUE), CATALOGUE, PRIMARY_MODEL.HEADER);
    const path = (name: string) => options.find((o) => o.model === 'stock.picking' && o.name === name)?.path;

    expect(path('origin')).toBe('origin');
    expect(options.find((o) => o.model === 'sale.order' && o.name === 'name')?.path)
      .toBe('sale.order:name');
  });

  it('distinguishes the same field name on two models', () => {
    const options = buildOptions(modelsFor('HEADER', CATALOGUE), CATALOGUE, PRIMARY_MODEL.HEADER);
    const names = options.filter((o) => o.name === 'name').map((o) => o.path);
    expect(names).toEqual(['name', 'sale.order:name']);
  });
});

describe('ranking', () => {
  const options = buildOptions(modelsFor('HEADER', CATALOGUE), CATALOGUE, PRIMARY_MODEL.HEADER);

  it('leads with the customer’s own fields when nothing is typed', () => {
    const partner = rank(options, '').filter((o) => o.model === 'res.partner');
    expect(partner[0].name).toBe('x_client_nom');
  });

  it('finds a custom field by a word in the middle of its name', () => {
    // The whole reason the native <select> had to go: its type-ahead only matched leading characters.
    expect(rank(options, 'client').map((o) => o.name)).toContain('x_client_nom');
  });

  it('matches the human label, not just the technical name', () => {
    expect(rank(options, 'customer reference').map((o) => o.name)).toContain('client_order_ref');
  });

  it('prefers an exact name over a field that merely contains it', () => {
    expect(rank(options, 'name')[0].name).toBe('name');
  });

  it('keeps each model contiguous so its header is rendered once', () => {
    const models = rank(options, 'name').map((o) => o.model);
    expect(models).toEqual([...new Set(models)].flatMap(
      (m) => models.filter((x) => x === m)));
  });

  it('excludes what does not match at all', () => {
    expect(rank(options, 'zzzz')).toEqual([]);
  });
});
