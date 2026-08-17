import { describe, expect, it } from 'vitest';
import { buildOptions, modelsFor, primaryModelFor, rank, type MappingScopes } from './mappingScope';
import type { ErpField } from '@/lib/api/erpIntegration';

// `char` / TEXT: these cases are about path building, not compatibility, so the widest source type
// keeps them independent of which canonical field a row happens to target.
const f = (name: string, label: string, custom = false): ErpField =>
  ({ name, label, type: 'char', sourceType: 'TEXT', custom });

/**
 * What the backend serves for an Odoo tenant. Hardcoded in the test on purpose: the point of these
 * cases is that the frontend builds the right path *given* a scope, not that it knows Odoo's models.
 */
const ODOO_SCOPES: MappingScopes = {
  HEADER: { primary: 'stock.picking', addressable: ['stock.picking', 'sale.order', 'res.partner'] },
  LINE: {
    primary: 'stock.move',
    addressable: ['stock.move', 'product.product', 'sale.order.line',
                  'stock.picking', 'sale.order', 'res.partner'],
  },
};

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
    const models = modelsFor('LINE', CATALOGUE, ODOO_SCOPES);
    expect(models[0]).toBe('stock.move');
    expect(models).toContain('sale.order');
    expect(models.indexOf('stock.move')).toBeLessThan(models.indexOf('stock.picking'));
  });

  it('never offers a header row a line model, which it could not read', () => {
    const models = modelsFor('HEADER', CATALOGUE, ODOO_SCOPES);
    expect(models).not.toContain('stock.move');
    expect(models).not.toContain('sale.order.line');
    expect(models).not.toContain('product.product');
  });

  it('offers nothing until the backend has said what is in scope', () => {
    // Guessing a default here is exactly what this refactor removed: an ERPNext tenant would have
    // been handed Odoo's documents.
    expect(modelsFor('HEADER', CATALOGUE, {})).toEqual([]);
    expect(primaryModelFor('HEADER', {})).toBeUndefined();
    expect(buildOptions(['stock.picking'], CATALOGUE, undefined)).toEqual([]);
  });

  it('drops models this ERP did not report rather than showing an empty group', () => {
    expect(modelsFor('HEADER', { 'stock.picking': [f('name', 'Reference')] }, ODOO_SCOPES))
      .toEqual(['stock.picking']);
  });
});

describe('path building', () => {
  // The rule the backend depends on: a bare line path is read against the stock move, so a line
  // field must be stored bare and a picking field must be qualified — the reverse of a header row.
  it('stores a line field bare and qualifies everything else', () => {
    const options = buildOptions(modelsFor('LINE', CATALOGUE, ODOO_SCOPES), CATALOGUE, primaryModelFor('LINE', ODOO_SCOPES));
    const path = (name: string) => options.find((o) => o.name === name)?.path;

    expect(path('x_lot_number')).toBe('x_lot_number');
    expect(path('origin')).toBe('stock.picking:origin');
    expect(path('city')).toBe('res.partner:city');
  });

  it('stores a header field bare against the picking', () => {
    const options = buildOptions(modelsFor('HEADER', CATALOGUE, ODOO_SCOPES), CATALOGUE, primaryModelFor('HEADER', ODOO_SCOPES));
    const path = (name: string) => options.find((o) => o.model === 'stock.picking' && o.name === name)?.path;

    expect(path('origin')).toBe('origin');
    expect(options.find((o) => o.model === 'sale.order' && o.name === 'name')?.path)
      .toBe('sale.order:name');
  });

  it('distinguishes the same field name on two models', () => {
    const options = buildOptions(modelsFor('HEADER', CATALOGUE, ODOO_SCOPES), CATALOGUE, primaryModelFor('HEADER', ODOO_SCOPES));
    const names = options.filter((o) => o.name === 'name').map((o) => o.path);
    expect(names).toEqual(['name', 'sale.order:name']);
  });
});

describe('ranking', () => {
  const options = buildOptions(modelsFor('HEADER', CATALOGUE, ODOO_SCOPES), CATALOGUE, primaryModelFor('HEADER', ODOO_SCOPES));

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

  it('finds a document by name when no field answers to the word', () => {
    expect(rank(options, 'partner').every((o) => o.model === 'res.partner')).toBe(true);
  });

  it('still puts a field that matches ahead of one that only shares a model name', () => {
    // "order" hits sale.order's name/label and the res.partner model not at all; the fields win.
    const first = rank(options, 'order')[0];
    expect(`${first.name} ${first.label}`.toLowerCase()).toContain('order');
  });

  it('excludes what does not match at all', () => {
    expect(rank(options, 'zzzz')).toEqual([]);
  });
});
