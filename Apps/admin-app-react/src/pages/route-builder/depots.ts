/**
 * Which depots a route stop is loaded from.
 *
 * <p>Exists because four places asked the question and three answered it wrong. `sourceDepotId`
 * names a single depot, so comparing it worked only for an ERP that issues one delivery note per
 * warehouse; for one that puts the warehouse on the line, the second pickup of a two-warehouse
 * order matched nothing and reported having nothing to load.
 *
 * Reads the server's `sourceDepotIds` and falls back to the single field, so a stop coming from an
 * older payload still answers as it used to.
 */
export function depotsOfStop(stop: {
  sourceDepotId?: string | null;
  sourceDepotIds?: string[] | null;
}): string[] {
  if (stop.sourceDepotIds?.length) return stop.sourceDepotIds;
  return stop.sourceDepotId ? [stop.sourceDepotId] : [];
}

/** Whether this stop's goods are collected at `depotId`. */
export function isLoadedAtDepot(
  stop: { sourceDepotId?: string | null; sourceDepotIds?: string[] | null },
  depotId: string | null | undefined,
): boolean {
  return !!depotId && depotsOfStop(stop).includes(depotId);
}
