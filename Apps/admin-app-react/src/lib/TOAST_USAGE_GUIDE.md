# Toast System - Global Implementation Guide

## Overview
The new toast system provides automatic, intelligent, and multilingual toast notifications throughout the application. All toasts include:
- ✅ Translated messages (FR/EN/AR)
- ✅ Order/Delivery context (ERP ID, Client name, Order ID)
- ✅ Timestamp
- ✅ Action type

## Usage

### Option 1: Automatic Toasts with API Wrapper (RECOMMENDED)

Use `apiWithToasts` instead of `api` for operations that should automatically show toasts:

```typescript
import { apiWithToasts } from '@/lib/api-with-toasts';

// Simple usage - auto shows translated error/success
await apiWithToasts.post(
  `/api/admin/routes/${routeId}/stops/${stopId}/cancel`,
  null,
  { params: { reason } },
  {
    showSuccess: true,
    successMessage: 'Arrêt retiré de la tournée',
    showError: true,
  }
);
```

### Option 2: Manual Toast Control

For more control, use the toast service directly:

```typescript
import { showSuccessToast, showErrorToast, extractContextFromResponse } from '@/lib/toast-service';

try {
  const response = await api.post('/api/...');
  const context = extractContextFromResponse(response.data);
  showSuccessToast('Action completed', context);
} catch (error) {
  const context = extractContextFromResponse(error.response?.data);
  showErrorToast(error.response?.data?.message, undefined, context);
}
```

### Option 3: Simple One-Liner

For pages that don't need automatic context extraction:

```typescript
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';

showSuccessToast('Opération réussie');
showErrorToast(error.message, 'Une erreur est survenue');
```

## Toast Configuration

### ToastConfig Options

```typescript
interface ToastConfig {
  showSuccess?: boolean;           // Show success toast (default: true)
  showError?: boolean;             // Show error toast (default: true)
  successMessage?: string;         // Custom success message
  errorMessage?: string;           // Custom error message
  extractContext?: (data) => any;  // Custom context extractor
}
```

### Context Information

```typescript
interface ToastContext {
  orderId?: string;      // Order/Delivery reference ID
  erpId?: string;        // ERP order ID
  clientName?: string;   // Customer name
  routeName?: string;    // Route name
  driverName?: string;   // Driver name
  deliveryId?: string;   // Delivery ID
  action?: string;       // Action type
}
```

### Toast Output Format

```
Title: "Arrêt retiré de la tournée"
Description: "Cmd: #ORDER-123 • ERP: 67890 • Client Company • Rte: Route #1 • 14:32"
```

## Translation Keys

All messages are translated in `apiMessages` section of UX_COPY:

### Success Messages
- `successStopCancelled` - Arrêt retiré de la tournée
- `successStopRemoved` - Arrêt supprimé
- `successWindowUpdated` - Fenêtre horaire mise à jour
- `successRouteValidated` - Tournée validée
- `successDeliveryReassigned` - Livraison réaffectée
- ... (50+ more)

### Error Messages
- `errorStopNotFound` - Arrêt non trouvé
- `errorStopInTransit` - Impossible d'annuler un arrêt en cours
- `errorStopCancellationNotAllowed` - Annulation non autorisée
- `errorWindowInvalid` - Fenêtre horaire invalide
- `errorCapacityExceeded` - Capacité véhicule dépassée
- ... (30+ more)

## Implementation Steps

1. **Replace `api` with `apiWithToasts`** in operations that need toasts:
   ```typescript
   // Before
   await api.post('/api/admin/...');

   // After
   await apiWithToasts.post(
     '/api/admin/...',
     data,
     config,
     { successMessage: 'Success!' }
   );
   ```

2. **Remove manual toast.success/error calls** for those operations

3. **Keep manual toasts** only for:
   - Custom UI interactions (copying links, etc.)
   - Analytics tracking
   - Non-API operations

## Automatic Error Translation

Backend errors are automatically translated based on the error message:

```typescript
// Backend returns: "Stop not found"
// Frontend shows: "Arrêt non trouvé" (translated from apiMessages.errorStopNotFound)

// Backend returns: "Stop cancellation only allowed on VALIDATED routes"
// Frontend shows: "Annulation d'arrêt autorisée uniquement sur les tournées validées"
```

## Custom Context Extraction

For custom context extraction from API responses:

```typescript
await apiWithToasts.post('/api/...', data, {}, {
  successMessage: 'Success',
  extractContext: (responseData) => ({
    orderId: responseData.delivery.orderId,
    erpId: responseData.delivery.erpOrderId,
    clientName: responseData.delivery.clientName,
  })
});
```

## Multi-Language Support

Toasts automatically use the current locale from `useLocaleStore`:
- **FR** (Français) - Default
- **EN** (English)
- **AR** (العربية)

Change via language switcher - toasts instantly adapt!

## Notes

- Timestamps are localized to user's language
- All backend error messages are mapped to predefined keys
- Failed mappings fall back to original backend message
- Toast descriptions are automatically formatted for readability
- System works across all pages without code duplication
