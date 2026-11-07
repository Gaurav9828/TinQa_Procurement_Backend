package com.tinqa.procurement.barcode.service;

import com.tinqa.procurement.barcode.enums.BarcodeStatus;
import com.tinqa.procurement.barcode.enums.BarcodeUnitType;
import com.tinqa.procurement.barcode.repository.BarcodeRepository;
import com.tinqa.procurement.common.exception.ApiException;
import com.tinqa.procurement.stock.dto.ItemStockMovementDTOs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of the draft check, without a database: which codes are read, and the problem each one has. */
class ProductUnitCheckTest {

    private record Live(String code, BarcodeUnitType unitType, BarcodeStatus status, Long itemId, String itemName)
            implements BarcodeRepository.LiveCode {
        public String getCode() { return code; }
        public BarcodeUnitType getUnitType() { return unitType; }
        public BarcodeStatus getStatus() { return status; }
        public Long getItemId() { return itemId; }
        public String getItemName() { return itemName; }
    }

    private static ItemStockMovementDTOs.DraftUnit unit(String barcode, ItemStockMovementDTOs.DraftPart... parts) {
        return new ItemStockMovementDTOs.DraftUnit(barcode, Arrays.asList(parts));
    }

    private static ItemStockMovementDTOs.DraftPart part(Long itemId, String... barcodes) {
        return new ItemStockMovementDTOs.DraftPart(itemId, Arrays.asList(barcodes));
    }

    @Test
    void entriesAreNormalizedAndBlankOrMalformedCodesSkipped() {
        List<ProductUnitCheck.Entry> entries = ProductUnitCheck.entries(Arrays.asList(
                unit(" unit0001 ", part(5L, "piece001", "", null, "ab", "-BAD-CODE-")),
                null,
                new ItemStockMovementDTOs.DraftUnit(null, null),
                unit("", part(null, "PIECE002"))));

        assertThat(entries).containsExactly(
                new ProductUnitCheck.Entry("UNIT0001", true, null),
                new ProductUnitCheck.Entry("PIECE001", false, 5L),
                new ProductUnitCheck.Entry("PIECE002", false, null));
        assertThat(ProductUnitCheck.entries(null)).isEmpty();
    }

    @Test
    void moreThanTheCodeLimitIsRefused() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i <= BarcodeCodes.MAX_CODES; i++) {
            codes.add(String.format("TQ%08d", i));
        }
        assertThatThrownBy(() -> ProductUnitCheck.entries(List.of(new ItemStockMovementDTOs.DraftUnit(null,
                List.of(new ItemStockMovementDTOs.DraftPart(1L, codes))))))
                .isInstanceOf(ApiException.class)
                .hasMessage("At most 5000 barcodes can be checked at once (5001 entered)");
    }

    @Test
    void eachRuleGivesTheSubmitMessageOncePerCodeInOrder() {
        List<ProductUnitCheck.Entry> entries = List.of(
                new ProductUnitCheck.Entry("FREEUNIT1", true, null),
                new ProductUnitCheck.Entry("TAKENUNIT", true, null),
                new ProductUnitCheck.Entry("GOODPIECE", false, 5L),
                new ProductUnitCheck.Entry("UNKNOWN01", false, 5L),
                new ProductUnitCheck.Entry("PRODUNIT1", false, 5L),
                new ProductUnitCheck.Entry("OTHERITEM", false, 5L),
                new ProductUnitCheck.Entry("FITTED001", false, 5L),
                new ProductUnitCheck.Entry("FITTED001", false, 5L),
                new ProductUnitCheck.Entry("OTHERITEM", false, null));
        Map<String, BarcodeRepository.LiveCode> live = Map.of(
                "TAKENUNIT", new Live("TAKENUNIT", BarcodeUnitType.PRODUCT_UNIT, BarcodeStatus.IN_STOCK, null, null),
                "GOODPIECE", new Live("GOODPIECE", BarcodeUnitType.ITEM_UNIT, BarcodeStatus.IN_STOCK, 5L, "ESP32 board"),
                "PRODUNIT1", new Live("PRODUNIT1", BarcodeUnitType.PRODUCT_UNIT, BarcodeStatus.IN_STOCK, null, null),
                "OTHERITEM", new Live("OTHERITEM", BarcodeUnitType.ITEM_UNIT, BarcodeStatus.IN_STOCK, 6L, "Relay"),
                "FITTED001", new Live("FITTED001", BarcodeUnitType.ITEM_UNIT, BarcodeStatus.FITTED, 5L, "ESP32 board"));

        List<ItemStockMovementDTOs.CodeProblem> problems = ProductUnitCheck.problems(entries, live);

        assertThat(problems).extracting(ItemStockMovementDTOs.CodeProblem::getCode)
                .containsExactly("TAKENUNIT", "UNKNOWN01", "PRODUNIT1", "OTHERITEM", "FITTED001");
        assertThat(problems).extracting(ItemStockMovementDTOs.CodeProblem::getMessage).containsExactly(
                "Barcode TAKENUNIT is already in use",
                "Barcode UNKNOWN01 is not registered",
                "Barcode PRODUNIT1 is a product unit, not a component piece",
                "Barcode OTHERITEM is a piece of Relay, not of the component it is listed under",
                "Barcode FITTED001 (ESP32 board) is not in stock: it is fitted in a product");
    }

    @Test
    void aPieceWithNoComponentChosenIsOnlyCheckedForStock() {
        assertThat(ProductUnitMovements.pieceProblem("OTHERITEM", BarcodeUnitType.ITEM_UNIT, BarcodeStatus.IN_STOCK, 6L, null,
                () -> "Relay")).isNull();
        assertThat(ProductUnitMovements.pieceProblem("OTHERITEM", BarcodeUnitType.ITEM_UNIT, BarcodeStatus.ON_DEMO, 6L, null,
                () -> "Relay")).isEqualTo("Barcode OTHERITEM (Relay) is not in stock: it is out on a client demo");
    }
}
