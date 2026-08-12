package com.asm.delivery.service;

import com.asm.delivery.entity.CashCollection;
import com.asm.delivery.entity.CashRemittance;
import com.asm.delivery.entity.CashRemittanceStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.CashCollectionRepository;
import com.asm.delivery.repository.CashRemittanceRepository;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The handover of a driver's cash, and the two-party count that makes it mean something.
 *
 * <h2>The rule everything else serves</h2>
 * The driver states a figure; somebody else counts. Neither can do the other's step. Without that
 * separation the module is an honour system with extra database columns — a driver who declares and
 * confirms his own handover has simply typed a number twice.
 *
 * <p>The discrepancy is always {@code counted − what the platform knows he took}, computed here.
 * It is never accepted from a caller, because a discrepancy someone can type is a discrepancy
 * someone can set to zero.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CashRemittanceService {

    private final CashRemittanceRepository remittanceRepo;
    private final CashCollectionRepository collectionRepo;
    private final OrderRepository orderRepository;
    private final AuditLogService auditLogService;

    private static final List<CashRemittanceStatus> IN_FLIGHT =
            List.of(CashRemittanceStatus.OPEN, CashRemittanceStatus.DECLARED);

    // ══════════════════════════════════════════════════════════════════════════
    //  Step 1 — the driver declares
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The driver says how much he is handing over, and every collection he still holds is attached.
     *
     * <p>{@code expectedTotal} is computed from those collections rather than taken from the request:
     * it is the platform's own account of what he took, and the one figure in the row neither party
     * can move.
     */
    @Transactional
    public CashRemittance declare(UUID driverId, String driverName, BigDecimal declaredTotal, UserPrincipal actor) {
        if (declaredTotal == null || declaredTotal.signum() < 0) {
            throw AppException.badRequest("Le montant déclaré doit être positif.");
        }

        List<CashCollection> held = collectionRepo.findByDriverIdAndRemittanceIdIsNull(driverId);
        List<CashCollection> withMoney = held.stream()
                .filter(c -> c.getAmountCollected() != null && c.getAmountCollected().signum() > 0)
                .toList();
        if (withMoney.isEmpty()) {
            throw AppException.badRequest("Aucun encaissement à remettre.");
        }

        // A second declaration would split one pocket of cash across two handovers, and neither
        // could then be counted against anything meaningful.
        remittanceRepo.findFirstByDriverIdAndStatusIn(driverId, IN_FLIGHT).ifPresent(open -> {
            throw AppException.badRequest(
                    "Une remise est déjà en cours pour ce livreur (" + open.getStatus() + ").");
        });

        BigDecimal expected = withMoney.stream()
                .map(CashCollection::getAmountCollected)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CashRemittance remittance = remittanceRepo.save(CashRemittance.builder()
                .driverId(driverId)
                .driverName(driverName)
                .status(CashRemittanceStatus.DECLARED)
                .expectedTotal(expected)
                .declaredTotal(declaredTotal)
                .declaredBy(actorId(actor))
                .declaredAt(LocalDateTime.now())
                .build());

        withMoney.forEach(c -> c.setRemittanceId(remittance.getId()));
        collectionRepo.saveAll(withMoney);

        log.info("CASH_REMITTANCE_DECLARED remittanceId={} driverId={} expected={} declared={} lines={}",
                remittance.getId(), driverId, expected, declaredTotal, withMoney.size());
        audit(actor, "CASH_REMITTANCE_DECLARED", remittance,
                "déclaré " + declaredTotal + " pour " + expected + " attendu");
        return remittance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Step 2 — somebody else counts
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * The depot counts the money in front of the driver and records what it found.
     *
     * <p>Refuses to let the declaring party also be the counting party. That check is the module: a
     * driver who can confirm his own handover has typed a number twice, and every table around it
     * becomes decoration.
     */
    @Transactional
    public CashRemittance receive(UUID remittanceId, BigDecimal receivedTotal, String note, UserPrincipal actor) {
        if (receivedTotal == null || receivedTotal.signum() < 0) {
            throw AppException.badRequest("Le montant compté doit être positif.");
        }
        CashRemittance r = load(remittanceId);
        if (r.getStatus() != CashRemittanceStatus.DECLARED) {
            throw AppException.badRequest(
                    "Cette remise ne peut pas être comptée : elle est en " + r.getStatus() + ".");
        }

        UUID counter = actorId(actor);
        if (counter != null && counter.equals(r.getDeclaredBy())) {
            throw AppException.badRequest("CASH_SELF_RECEIVE",
                    "La personne qui a déclaré la remise ne peut pas la compter.");
        }
        if (counter != null && counter.equals(r.getDriverId())) {
            throw AppException.badRequest("CASH_SELF_RECEIVE",
                    "Un livreur ne peut pas compter sa propre remise.");
        }

        BigDecimal discrepancy = receivedTotal.subtract(r.getExpectedTotal());
        boolean balanced = discrepancy.signum() == 0;

        r.setReceivedTotal(receivedTotal);
        r.setDiscrepancy(discrepancy);
        r.setReceivedBy(counter);
        r.setReceivedByName(actorName(actor));
        r.setReceivedAt(LocalDateTime.now());
        // Its own column: settling writes `note`, and sharing one meant the manager's explanation
        // erased whatever the counter had observed.
        r.setCountNote(trimToNull(note));
        r.setStatus(balanced ? CashRemittanceStatus.RECONCILED : CashRemittanceStatus.DISPUTED);
        if (balanced) {
            r.setReconciledBy(counter);
            r.setReconciledAt(LocalDateTime.now());
            r.setClosedAt(LocalDateTime.now());
        }

        log.info("CASH_REMITTANCE_RECEIVED remittanceId={} expected={} received={} discrepancy={} status={}",
                remittanceId, r.getExpectedTotal(), receivedTotal, discrepancy, r.getStatus());
        audit(actor, "CASH_REMITTANCE_RECEIVED", r,
                "compté " + receivedTotal + " pour " + r.getExpectedTotal() + " attendu — écart " + discrepancy);
        return remittanceRepo.save(r);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Step 3 — a manager settles what did not balance
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Close a handover whose figures disagreed, with a written explanation.
     *
     * <p>The note is mandatory. A discrepancy closed without one is indistinguishable from a
     * discrepancy hidden, and the difference is the only thing an audit later has to go on.
     */
    @Transactional
    public CashRemittance reconcile(UUID remittanceId, String note, UserPrincipal actor) {
        String explanation = trimToNull(note);
        if (explanation == null) {
            throw AppException.badRequest("Une explication est obligatoire pour justifier un écart.");
        }
        CashRemittance r = load(remittanceId);
        if (r.getStatus() != CashRemittanceStatus.DISPUTED) {
            throw AppException.badRequest(
                    "Seule une remise en écart peut être justifiée (état actuel : " + r.getStatus() + ").");
        }

        // The last step of the chain was the only one with no separation on it.
        //
        // Declaring and counting are held apart by identity — a driver cannot confirm his own
        // handover — and then anyone who could reach the desk could close the gap that count had
        // just produced. The person who counted 900 against 902,750 could sign off the 2,750 alone,
        // which is precisely the arrangement the two-party count exists to prevent.
        //
        // By role rather than by identity: this class has always said "a manager settles it", and a
        // depot with one cashier on duty must still be able to count. What it may not do is close
        // its own discrepancy without anyone above it.
        if (!isManager(actor)) {
            throw AppException.forbidden("CASH_RECONCILE_FORBIDDEN",
                    "Seul un responsable peut justifier un écart de caisse.");
        }

        r.setNote(explanation);
        r.setReconciledBy(actorId(actor));
        r.setReconciledAt(LocalDateTime.now());
        r.setClosedAt(LocalDateTime.now());
        r.setStatus(CashRemittanceStatus.RECONCILED);

        log.info("CASH_REMITTANCE_RECONCILED remittanceId={} discrepancy={}", remittanceId, r.getDiscrepancy());
        audit(actor, "CASH_REMITTANCE_RECONCILED", r,
                "écart " + r.getDiscrepancy() + " soldé : " + explanation);
        return remittanceRepo.save(r);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Reads
    // ══════════════════════════════════════════════════════════════════════════

    /** What a driver is holding right now — nothing handed over yet. */
    @Transactional(readOnly = true)
    public BigDecimal outstandingForDriver(UUID driverId) {
        BigDecimal v = collectionRepo.outstandingForDriver(driverId);
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Cash in circulation across the fleet: everything collected and not yet handed over.
     *
     * <p>No ERP can produce this. It describes a state of the field between two accounting entries.
     */
    @Transactional(readOnly = true)
    public BigDecimal cashInCirculation() {
        BigDecimal v = collectionRepo.outstandingTotal();
        return v != null ? v : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public List<CashCollection> collectionsOf(UUID remittanceId) {
        return collectionRepo.findByRemittanceId(remittanceId);
    }

    /**
     * Handovers for the depot's desk.
     *
     * <p>Defaults to the ones still needing a human — declared but uncounted, or counted and
     * disputed. A desk that opens on every handover ever made buries the two that need acting on
     * today under a year of settled ones.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<CashRemittance> list(
            CashRemittanceStatus status, org.springframework.data.domain.Pageable pageable) {
        List<CashRemittanceStatus> wanted = status != null
                ? List.of(status)
                : List.of(CashRemittanceStatus.DECLARED, CashRemittanceStatus.DISPUTED);
        return remittanceRepo.findByStatusIn(wanted, pageable);
    }

    /**
     * How many handovers sit in each state.
     *
     * <p>The desk's filter bar could only ever number the tab you were already on, so it answered
     * the question you had just answered yourself and stayed silent on the one that matters —
     * whether anything is waiting on a tab you are not looking at. States with nothing in them come
     * back as zero rather than being omitted, so no tab renders without a figure.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (CashRemittanceStatus s : CashRemittanceStatus.values()) counts.put(s.name(), 0L);
        for (Object[] row : remittanceRepo.countByStatus()) {
            counts.put(((CashRemittanceStatus) row[0]).name(), (Long) row[1]);
        }
        return counts;
    }

    /**
     * What one handover is actually made of, named the way the counter names it.
     *
     * <p>Returned the entity before, so a disagreement about one delivery note could only be
     * followed by reading two UUIDs off the screen and looking them up elsewhere.
     */
    @Transactional(readOnly = true)
    public List<com.asm.delivery.dto.response.CashCollectionRow> collectionRowsOf(UUID remittanceId) {
        List<CashCollection> collections = collectionRepo.findByRemittanceId(remittanceId);
        if (collections.isEmpty()) return List.of();

        Map<UUID, Order> orders = orderRepository
                .findAllById(collections.stream().map(CashCollection::getOrderId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Order::getId, o -> o));

        return collections.stream().map(c -> {
            Order o = orders.get(c.getOrderId());
            return com.asm.delivery.dto.response.CashCollectionRow.builder()
                    .id(c.getId())
                    .deliveryId(c.getDeliveryId())
                    .blNumber(o != null ? o.resolveRef() : null)
                    .clientName(o != null ? o.getClientName() : null)
                    .amountExpected(c.getAmountExpected())
                    .amountCollected(c.getAmountCollected())
                    .method(c.getMethod() != null ? c.getMethod().name() : null)
                    .chequeNumber(c.getChequeNumber())
                    .chequeBank(c.getChequeBank())
                    .status(c.getStatus() != null ? c.getStatus().name() : null)
                    .reasonLabel(c.getReasonLabel())
                    .collectedAt(c.getCollectedAt())
                    .build();
        }).toList();
    }

    /** Per-driver outstanding cash, for the desk's "who is holding what" panel. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> outstandingByDriver() {
        return collectionRepo.outstandingByDriver().stream()
                .map(row -> Map.<String, Object>of(
                        "driverId", row[0],
                        "amount", row[1],
                        "collections", row[2]))
                .toList();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CashRemittance load(UUID id) {
        return remittanceRepo.findById(id)
                .orElseThrow(() -> AppException.notFound("Remise introuvable : " + id));
    }

    private void audit(UserPrincipal actor, String action, CashRemittance r, String detail) {
        try {
            auditLogService.logAction(actor, action, "CASH_REMITTANCE", String.valueOf(r.getId()), detail);
        } catch (Exception e) {
            // Money moved and the state changed; losing the audit line must not undo either.
            log.warn("CASH_AUDIT_FAILED action={} remittanceId={} reason={}", action, r.getId(), e.getMessage());
        }
    }

    private static UUID actorId(UserPrincipal actor) {
        if (actor == null || actor.getUserId() == null) return null;
        try {
            return UUID.fromString(actor.getUserId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String actorName(UserPrincipal actor) {
        return actor != null ? actor.getDisplayName() : null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Whether this actor may close a discrepancy.
     *
     * <p>Absent role is refused rather than waved through: the whole point of the check is that
     * money does not leave the books on the strength of something the platform could not read.
     */
    private static boolean isManager(UserPrincipal actor) {
        if (actor == null || !StringUtils.hasText(actor.getRole())) return false;
        String role = actor.getRole().trim().toUpperCase(java.util.Locale.ROOT);
        return role.equals(com.asm.delivery.entity.Role.MANAGER.name())
                || role.equals(com.asm.delivery.entity.Role.ADMIN.name());
    }
}
