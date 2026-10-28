package com.tinqa.procurement.barcode.service;

import com.tinqa.procurement.barcode.enums.BarcodeEventType;
import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Set-based writes of barcodes and their events, so a change to any number of units costs the same few statements
 * (a stock entry of 500 pieces is one insert batch, not 1000 inserts). Every change records one event per barcode,
 * written from the barcode's state just before the change.
 */
@Component
@RequiredArgsConstructor
class BarcodeWriter {

    private static final String EVENT_COLUMNS = "barcode_id, event_type, from_status, to_status, from_fitted_into_id, "
            + "to_fitted_into_id, movement_id, note, actor_id, actor_name, created_at";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** A unit to register. */
    record NewUnit(String code, BarcodeUnitType unitType, BarcodeStatus status, Long itemId, Long stockId,
                   Long productId, String productTitle) {
    }

    /** What happens to the fitted-into link of the barcodes a change moves. */
    enum Fitting { KEEP, CLEAR, SET }

    /**
     * A move of barcodes to a status. {@code fittedInto} (SET) gives each barcode the unit it is fitted into;
     * {@code lock} records the approval that locks the codes; a non-null {@code statusNote} replaces the note.
     */
    record Change(Collection<Long> ids, BarcodeStatus to, BarcodeEventType event, Fitting fitting, Map<Long, Long> fittedInto,
                  boolean lock, String statusNote, UUID movementId, String note, BarcodeRegistry.Actor actor) {

        static Change of(Collection<Long> ids, BarcodeStatus to, BarcodeEventType event, BarcodeRegistry.Actor actor) {
            return new Change(ids, to, event, Fitting.KEEP, Map.of(), false, null, null, null, actor);
        }

        Change locking() {
            return new Change(ids, to, event, fitting, fittedInto, true, statusNote, movementId, note, actor);
        }

        Change noted(String reason) {
            return new Change(ids, to, event, fitting, fittedInto, lock, statusNote, movementId, reason, actor);
        }

        Change withStatusNote(String text) {
            return new Change(ids, to, event, fitting, fittedInto, lock, text, movementId, note, actor);
        }

        Change inMovement(UUID id) {
            return new Change(ids, to, event, fitting, fittedInto, lock, statusNote, id, note, actor);
        }

        Change clearingFit() {
            return new Change(ids, to, event, Fitting.CLEAR, Map.of(), lock, statusNote, movementId, note, actor);
        }

        /** Each barcode (key) fitted into the unit (value). */
        static Change fitting(Map<Long, Long> fittedInto, UUID movementId, BarcodeRegistry.Actor actor) {
            return new Change(fittedInto.keySet(), BarcodeStatus.FITTED, BarcodeEventType.FITTED, Fitting.SET, fittedInto,
                    false, null, movementId, null, actor);
        }
    }

    /**
     * Registers the units in one batch, with their REGISTERED events, and returns their ids by code. {@code locked}:
     * the codes are locked straight away (nothing to approve).
     */
    Map<String, Long> register(List<NewUnit> units, boolean locked, UUID movementId, String note, BarcodeRegistry.Actor actor) {
        if (units.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        SqlParameterSource[] rows = units.stream().map(unit -> new MapSqlParameterSource()
                .addValue("code", unit.code())
                .addValue("unitType", unit.unitType().name())
                .addValue("status", unit.status().name())
                .addValue("itemId", unit.itemId())
                .addValue("stockId", unit.stockId())
                .addValue("productId", unit.productId())
                .addValue("productTitle", unit.productTitle())
                .addValue("lockedAt", locked ? now : null)
                .addValue("lockedBy", locked ? actor.id() : null)
                .addValue("createdBy", actor.id())
                .addValue("createdByName", actor.name())
                .addValue("now", now)).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO barcodes (code, unit_type, status, item_id, stock_id, ecommerce_product_id, product_title,
                                      locked_at, locked_by, created_by, created_by_name, created_at, updated_at, version)
                VALUES (:code, :unitType, :status, :itemId, :stockId, :productId, :productTitle,
                        CAST(:lockedAt AS timestamp), CAST(:lockedBy AS bigint), CAST(:createdBy AS bigint), :createdByName, :now, :now, 0)
                """, rows);

        List<String> codes = units.stream().map(NewUnit::code).toList();
        Map<String, Long> ids = new HashMap<>();
        jdbc.query("SELECT id, code FROM barcodes WHERE code IN (:codes) AND status <> 'VOID'", Map.of("codes", codes),
                rs -> {
                    ids.put(rs.getString("code"), rs.getLong("id"));
                });
        jdbc.update("INSERT INTO barcode_events (" + EVENT_COLUMNS + ") "
                        + "SELECT id, 'REGISTERED', NULL, status, NULL, fitted_into_id, CAST(:movementId AS uuid), :note, "
                        + "CAST(:actorId AS bigint), :actorName, :now FROM barcodes WHERE id IN (:ids)",
                eventParams(ids.values(), movementId, note, actor, now));
        return ids;
    }

    /** Moves the barcodes and records an event for each: two statements, whatever their number. */
    void apply(Change change) {
        if (change.ids().isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        MapSqlParameterSource params = eventParams(change.ids(), change.movementId(), change.note(), change.actor(), now)
                .addValue("to", change.to().name())
                .addValue("event", change.event().name())
                .addValue("lock", change.lock())
                .addValue("statusNote", change.statusNote());
        String source = "barcodes b";
        String match = "b.id IN (:ids)";
        String toFitted = switch (change.fitting()) {
            case KEEP -> "b.fitted_into_id";
            case CLEAR -> "NULL";
            case SET -> "v.unit_id";
        };
        if (change.fitting() == Fitting.SET) {
            List<Long> parts = new ArrayList<>(change.fittedInto().keySet());
            params.addValue("parts", parts.toArray(Long[]::new))
                    .addValue("units", parts.stream().map(change.fittedInto()::get).toArray(Long[]::new));
            source = "barcodes b JOIN unnest(CAST(:parts AS bigint[]), CAST(:units AS bigint[])) AS v(part_id, unit_id) ON v.part_id = b.id";
            match = "TRUE";
        }
        jdbc.update("INSERT INTO barcode_events (" + EVENT_COLUMNS + ") "
                + "SELECT b.id, :event, b.status, :to, b.fitted_into_id, " + toFitted + ", CAST(:movementId AS uuid), :note, "
                + "CAST(:actorId AS bigint), :actorName, :now FROM " + source + " WHERE " + match, params);

        String from = change.fitting() == Fitting.SET
                ? " FROM unnest(CAST(:parts AS bigint[]), CAST(:units AS bigint[])) AS v(part_id, unit_id) WHERE v.part_id = b.id"
                : " WHERE b.id IN (:ids)";
        jdbc.update("UPDATE barcodes b SET status = :to, fitted_into_id = " + toFitted + ", "
                + "locked_at = CASE WHEN :lock THEN CAST(:now AS timestamp) ELSE b.locked_at END, "
                + "locked_by = CASE WHEN :lock THEN CAST(:actorId AS bigint) ELSE b.locked_by END, "
                + "status_note = COALESCE(CAST(:statusNote AS varchar), b.status_note), updated_at = :now, version = b.version + 1"
                + from, params);
    }

    /** Ids of the entry's barcodes with the status, row-locked. */
    List<Long> lockStockUnits(Long stockId, BarcodeStatus status) {
        return jdbc.queryForList("SELECT id FROM barcodes WHERE stock_id = :stockId AND status = :status ORDER BY id FOR UPDATE",
                Map.of("stockId", stockId, "status", status.name()), Long.class);
    }

    /** Ids of the product's units with the status, row-locked. */
    List<Long> lockProductUnits(Long productId, BarcodeStatus status) {
        return jdbc.queryForList("SELECT id FROM barcodes WHERE unit_type = 'PRODUCT_UNIT' AND ecommerce_product_id = :productId "
                + "AND status = :status ORDER BY id FOR UPDATE", Map.of("productId", productId, "status", status.name()), Long.class);
    }

    // ------------------------------------------------------------------ reversal of a movement

    /** Whether every barcode the movement changed is still as it left it (and a code it frees back is still free). */
    boolean unchangedSince(UUID movementId) {
        Long changed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM barcode_events e JOIN barcodes b ON b.id = e.barcode_id
                WHERE e.movement_id = :movementId AND e.event_type <> 'REVERSED'
                  AND (b.status <> e.to_status OR b.fitted_into_id IS DISTINCT FROM e.to_fitted_into_id
                       OR (b.status = 'VOID' AND COALESCE(e.from_status, 'VOID') <> 'VOID'
                           AND EXISTS (SELECT 1 FROM barcodes o WHERE o.code = b.code AND o.id <> b.id AND o.status <> 'VOID')))
                """, Map.of("movementId", movementId), Long.class);
        return changed == null || changed == 0;
    }

    /** Puts every barcode the movement changed back as it was (a unit it registered is voided), recording each. */
    void reverse(UUID movementId, BarcodeRegistry.Actor actor) {
        LocalDateTime now = LocalDateTime.now(clock);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("movementId", movementId)
                .addValue("actorId", actor.id())
                .addValue("actorName", actor.name())
                .addValue("now", now);
        jdbc.update("INSERT INTO barcode_events (" + EVENT_COLUMNS + ") "
                + "SELECT b.id, 'REVERSED', b.status, COALESCE(e.from_status, 'VOID'), b.fitted_into_id, e.from_fitted_into_id, "
                + "e.movement_id, 'Item stock movement reversed', CAST(:actorId AS bigint), :actorName, :now "
                + "FROM barcode_events e JOIN barcodes b ON b.id = e.barcode_id "
                + "WHERE e.movement_id = CAST(:movementId AS uuid) AND e.event_type <> 'REVERSED'", params);
        jdbc.update("UPDATE barcodes b SET status = COALESCE(e.from_status, 'VOID'), fitted_into_id = e.from_fitted_into_id, "
                + "updated_at = :now, version = b.version + 1 FROM barcode_events e "
                + "WHERE e.barcode_id = b.id AND e.movement_id = CAST(:movementId AS uuid) AND e.event_type NOT IN ('REVERSED')", params);
    }

    private static MapSqlParameterSource eventParams(Collection<Long> ids, UUID movementId, String note,
                                                     BarcodeRegistry.Actor actor, LocalDateTime now) {
        return new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("movementId", movementId)
                .addValue("note", note)
                .addValue("actorId", actor.id())
                .addValue("actorName", actor.name())
                .addValue("now", now);
    }
}
