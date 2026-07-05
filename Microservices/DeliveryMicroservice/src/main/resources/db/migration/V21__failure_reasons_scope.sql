-- Replace the 4-value applies_to (FAILURE / ITEM_REFUSED / ITEM_DAMAGED / ITEM_MISSING) with a clean
-- 3-value scope. The three ITEM_* contexts duplicated the analytics `category` (and let a reason be
-- mis-placed, e.g. a REFUSED reason showing as "missing"). Now `category` drives the disposition and
-- `scope` only says where the reason is usable: DELIVERY (failure sheet), ITEM (line disposition), BOTH.
ALTER TABLE failure_reasons DROP COLUMN IF EXISTS applies_to;
ALTER TABLE failure_reasons ADD COLUMN scope VARCHAR(20) NOT NULL DEFAULT 'DELIVERY';

-- Re-scope the seed catalog: item-disposition categories become usable on lines too.
UPDATE failure_reasons SET scope = 'BOTH'
 WHERE category IN ('REFUSED', 'DAMAGED', 'MISSING');
