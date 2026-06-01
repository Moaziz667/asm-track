import * as XLSX from 'xlsx';
import { api } from './api';
import { Delivery } from '@/types';
import { formatDateFile } from './date';
import { format } from 'date-fns';

interface ExportFilters {
  status?: string;
  driverId?: string;
  source?: string;
}

export async function exportDeliveries(filters: ExportFilters = {}) {
  // Fetch all pages
  const allDeliveries: Delivery[] = [];
  let page = 0;
  const size = 100;

  while (true) {
    const params: Record<string, string | number> = { page, size };
    if (filters.status) params.status = filters.status;
    if (filters.driverId) params.driverId = filters.driverId;
    if (filters.source) params.source = filters.source;

    const res = await api.get('/api/admin/deliveries', { params });
    const data = res.data;
    const content = data.content ?? data;

    if (!Array.isArray(content) || content.length === 0) break;
    allDeliveries.push(...content);

    if (data.last === true || content.length < size) break;
    page++;
  }

  // Build workbook
  const wb = XLSX.utils.book_new();

  // Sheet 1: Livraisons
  const headers = [
    'Order ID', 'Ref ERP', 'Client', 'Telephone client',
    'Adresse', 'Ville', 'Statut', 'Livreur', 'Tel livreur',
    'Montant (TND)', 'Source',
    'Cree le',
  ];

  const rows = allDeliveries.map((d) => [
    d.orderId ?? '',
    d.erpId ?? '',
    d.clientName ?? '',
    d.clientPhone ?? '',
    d.dropoffAddress ?? '',
    d.dropoffCity ?? '',
    d.status ?? '',
    d.driverName ?? '',
    d.driverPhone ?? '',
    d.totalAmount ?? 0,
    d.source ?? '',
    d.createdAt ? format(new Date(d.createdAt), 'yyyy-MM-dd HH:mm') : '',
  ]);

  const ws1 = XLSX.utils.aoa_to_sheet([headers, ...rows]);

  // Auto column widths
  const colWidths = headers.map((h, i) => {
    const maxLen = Math.max(h.length, ...rows.map((r) => String(r[i]).length));
    return { wch: Math.min(maxLen + 2, 40) };
  });
  ws1['!cols'] = colWidths;

  XLSX.utils.book_append_sheet(wb, ws1, 'Livraisons');

  // Sheet 2: Resume
  const total = allDeliveries.length;
  const delivered = allDeliveries.filter((d) => d.status === 'DELIVERED').length;
  const failed = allDeliveries.filter((d) => d.status === 'FAILED').length;
  const successRate = total > 0 ? ((delivered / total) * 100).toFixed(1) : '0';
  const totalAmount = allDeliveries.reduce((sum, d) => sum + (d.totalAmount ?? 0), 0);

  const summaryData = [
    ['Genere le', new Date().toLocaleString('fr-TN')],
    ['Total livraisons', total],
    ['Livrees', delivered, `${total > 0 ? ((delivered / total) * 100).toFixed(1) : 0}%`],
    ['Echouees', failed, `${total > 0 ? ((failed / total) * 100).toFixed(1) : 0}%`],
    ['Taux de succes', `${successRate}%`],
    ['Montant total TND', totalAmount.toFixed(3)],
  ];

  const ws2 = XLSX.utils.aoa_to_sheet(summaryData);
  ws2['!cols'] = [{ wch: 20 }, { wch: 15 }, { wch: 10 }];
  XLSX.utils.book_append_sheet(wb, ws2, 'Resume');

  XLSX.writeFile(wb, `livraisons-${formatDateFile(new Date())}.xlsx`);
}
