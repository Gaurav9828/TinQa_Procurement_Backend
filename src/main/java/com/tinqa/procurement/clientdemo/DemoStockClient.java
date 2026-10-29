package com.tinqa.procurement.clientdemo;

import java.util.Map;

/**
 * The storefront's sellable stock of the product units taken to client demos (the e-commerce service owns it). A
 * hand-out lowers it before the units leave stock here, so nothing out on a demo can be sold; a return raises it
 * again. Each move carries a key and is applied once however often it is sent.
 */
public interface DemoStockClient {

    enum Direction {
        // Units leave the storefront's stock for a demo; refused when it no longer has that many
        OUT,
        // Units come back into the storefront's stock
        BACK
    }

    /**
     * Moves the units (product id → how many) in one go.
     *
     * @throws com.tinqa.procurement.common.exception.ApiException 409 when the storefront refuses (too few units left to
     *                                                             sell), 503 when it can't be reached
     */
    void move(String key, Direction direction, String demoReference, Map<Long, Integer> quantities, String performedBy);

    /** Undoes a move that was sent but could not be recorded here. Never throws; a failure is logged. */
    void reverse(String key);
}
