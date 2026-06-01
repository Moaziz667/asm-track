export function rowId(d: { id?: string; deliveryId?: string }): string {
  return d.deliveryId ?? d.id ?? '';
}

export function getWeekStart(): string {
  const d = new Date();
  d.setDate(d.getDate() - d.getDay() + 1);
  return d.toISOString().slice(0, 10);
}

export function getMonthStart(): string {
  const d = new Date();
  d.setDate(1);
  return d.toISOString().slice(0, 10);
}

export function sortByRoute<T extends { routeName?: string; routeId?: string }>(
  items: T[],
  secondaryScore: (item: T) => number,
): T[] {
  return [...items].sort((a, b) => {
    const rA = a.routeName ?? '';
    const rB = b.routeName ?? '';
    if (rA !== rB) return rA.localeCompare(rB);
    return secondaryScore(a) - secondaryScore(b);
  });
}
