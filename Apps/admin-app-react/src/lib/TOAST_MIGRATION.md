# Toast Migration Guide

## New Toast System

**Positioning:**
- `toast.info()` / `toast.warning()` → **top-left** (real-time alerts)
- `toast.error()` → **top-center** (errors need attention)
- `toast.success()` → **bottom-right** (CRUD operations)

**Auto-dismiss:**
- Success: 3s
- Error/Warning: 5s
- Info: 3s

---

## Usage

### Before
```tsx
import { toast } from '@/lib/toast';

toast.success('Livraison creee avec succes'); // ❌ typo, no context
toast.error('Erreur lors de la mise à jour');  // ❌ generic
```

### After
```tsx
import { toast } from '@/lib/toast';
import { messages } from '@/lib/toast-messages';

// With rich messages
const msg = messages.deliveries.createSuccess;
toast.success(msg.title, { description: msg.description });

// For dynamic messages
const importMsg = messages.drivers.importSuccess(5);
toast.success(importMsg.title);
```

---

## Message Structure

All messages have:
- `title` — Main message
- `description` (optional) — Additional context
- Dynamic functions for counts/variables

---

## Toast Types in Order of Priority

### 1. **Real-time Alerts** (top-left)
```tsx
toast.info('Chauffeur connecté', { description: 'Ahmed est maintenant en ligne' });
toast.warning('Livraison en retard', { description: '+15 min détecté' });
```

### 2. **Errors** (top-center)
```tsx
const err = messages.routes.validationFailed;
toast.error(err.title, { description: err.description });
```

### 3. **Success** (bottom-right)
```tsx
const msg = messages.zones.createSuccess;
toast.success(msg.title);
```

---

## Files to Update (Priority Order)

1. **useRouteActions.ts** — Route operations
2. **useDeliveries.ts** — Delivery loading
3. **ReassignDrawer.tsx** — Reassignment logic
4. **drivers/page.tsx** — Driver CRUD
5. **import/page.tsx** — Import batch/single
6. **depots/page.tsx** — Hub management
7. **zones/page.tsx** — Zone management
8. **companies/page.tsx** — Company CRUD
9. **NewDeliveryPanel.tsx** — New delivery creation
10. **useUndoableAction.ts** — Undo/redo

---

## Example Refactor

**File: `src/features/routes/hooks/useRouteActions.ts`**

Before:
```tsx
try {
  await api.post(`/api/admin/routes/${routeId}/validate`);
  toast.success('Tournée validée');
} catch (err) {
  toast.error(err?.response?.data?.message ?? 'Erreur lors de la validation');
}
```

After:
```tsx
import { messages } from '@/lib/toast-messages';

try {
  await api.post(`/api/admin/routes/${routeId}/validate`);
  const msg = messages.routes.validationSuccess;
  toast.success(msg.title, { description: msg.description });
} catch (err) {
  const msg = messages.routes.validationFailed;
  toast.error(msg.title, { 
    description: msg.description 
  });
}
```

---

## Testing

1. Clear app cache
2. Restart dev server
3. Trigger each action and verify:
   - ✅ Message appears in correct position
   - ✅ Auto-dismiss timing is correct
   - ✅ Message is informative and typo-free
   - ✅ Dark mode works

---

## Next: Automated Migration Script?

If refactoring all 50+ toast calls manually is tedious, we can generate a script to do it. Let me know!
