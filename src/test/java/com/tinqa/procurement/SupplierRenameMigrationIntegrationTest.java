package com.tinqa.procurement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V24 renames dealer to supplier in a database that already holds dealer data: tables and columns, the stored codes
 * (role, designation, order status, permission group), JSONB keys and the Approvals Hub function, while names and
 * emails typed by people stay as they were.
 */
class SupplierRenameMigrationIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate db;

    @BeforeAll
    static void migrateOldDataThroughV24() {
        POSTGRES.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        db = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("23").load().migrate();

        db.update("INSERT INTO users (id, username, email, password, role, auth_client) VALUES "
                + "(1, 'abc', 'dealer@abc.example', 'x', 'DEALER', 'WEB'), (2, 'mgr', 'mgr@x.example', 'x', 'ADMIN', 'WEB')");
        db.update("INSERT INTO dealers (id, name, email, phone_number, street, city, state, pincode) VALUES "
                + "(1, 'ABC Dealers Pvt Ltd', 'dealer@abc.example', '9000000000', 'MG Road', 'Pune', 'MH', '411001')");
        db.update("INSERT INTO dealer_login (dealer_id, user_id, status, created_by, created_at, updated_at) "
                + "VALUES (1, 1, 'APPROVAL_PENDING', 2, now(), now())");
        db.update("INSERT INTO items (id, name, sku, unit_of_measure, mrp, country_of_origin) "
                + "VALUES (1, 'Sensor', 'SKU-1', 'PCS', 10, 'India')");
        db.update("INSERT INTO orders (order_number, dealer_id, item_id, order_quantity, unit_type, unit_price, "
                + "total_price, order_status, order_date, additional_info, created_by) VALUES "
                + "('PO-1', 1, 1, 1, 'PCS', 10, 10, 'DEALER_LEVEL_PENDING', current_date, "
                + "'{\"dealerNote\": \"Dealer note\", \"stage\": \"DEALER_LEVEL_PENDING\"}', 1), "
                + "('PO-2', 1, 1, 1, 'PCS', 10, 10, 'QUOTATION', current_date, '{}', 1)");

        Flyway.configure().dataSource(ds).load().migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void tablesColumnsAndIndexesAreNamedAfterSuppliers() {
        List<String> names = db.queryForList("SELECT relname::text FROM pg_class c JOIN pg_namespace n "
                + "ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND relname <> 'flyway_schema_history' "
                + "UNION ALL SELECT attname::text FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r' AND a.attnum > 0 "
                + "UNION ALL SELECT conname::text FROM pg_constraint", String.class);
        assertThat(names).noneMatch(n -> n.contains("dealer"))
                .contains("suppliers", "supplier_login", "supplier_payment", "supplier_category_mappings",
                        "supplier_id", "idx_orders_supplier");
    }

    @Test
    void storedCodesAndJsonKeysSaySupplierButTypedTextIsKept() {
        assertThat(db.queryForObject("SELECT role FROM users WHERE id = 1", String.class)).isEqualTo("SUPPLIER");
        assertThat(db.queryForObject("SELECT order_status FROM orders WHERE order_number = 'PO-1'", String.class))
                .isEqualTo("SUPPLIER_LEVEL_PENDING");
        assertThat(db.queryForObject("SELECT additional_info::text FROM orders WHERE order_number = 'PO-1'",
                String.class)).isEqualTo("{\"stage\": \"SUPPLIER_LEVEL_PENDING\", \"supplierNote\": \"Dealer note\"}");
        assertThat(db.queryForMap("SELECT name, email FROM suppliers WHERE id = 1"))
                .containsEntry("name", "ABC Dealers Pvt Ltd").containsEntry("email", "dealer@abc.example");
    }

    @Test
    void checkConstraintsAcceptOnlyTheNewCodes() {
        assertThatThrownBy(() -> db.update("UPDATE orders SET order_status = 'DEALER_LEVEL_PENDING' WHERE id > 0"))
                .hasMessageContaining("chk_orders_status");
        assertThat(db.update("UPDATE orders SET order_status = 'SUPPLIER_LEVEL_PENDING' WHERE order_number = 'PO-2'"))
                .isOne();
        db.update("UPDATE orders SET order_status = 'QUOTATION' WHERE order_number = 'PO-2'");
    }

    @Test
    void theApprovalsHubCountsSupplierQueues() {
        assertThat(db.queryForObject("SELECT waiting FROM approval_queue_counts(ARRAY['SUPPLIER_LOGINS']) "
                + "WHERE queue = 'SUPPLIER_LOGINS'", Long.class)).isOne();
        assertThat(db.queryForObject("SELECT waiting FROM approval_queue_counts(ARRAY['QUOTATIONS']) "
                + "WHERE queue = 'QUOTATIONS'", Long.class)).isOne();
    }
}
