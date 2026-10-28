package com.tinqa.procurement.item.service;

import com.tinqa.procurement.common.constant.AppConstants;
import com.tinqa.procurement.common.util.ReferenceNumberGenerator;
import com.tinqa.procurement.item.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Item SKUs are always generated: TQ-yyyyMMdd-NNNN, numbered from 0001 each day. SKUs are never reused or edited.
 */
@Component
@RequiredArgsConstructor
public class ItemSkuGenerator {

    private final ItemRepository itemRepository;
    private final ReferenceNumberGenerator referenceNumbers;

    /**
     * The next free SKU for today. Must run inside the transaction that saves the item: the lock is held until it
     * commits, so two items created at once can never get the same number.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        itemRepository.lockSkuSequence();
        String prefix = referenceNumbers.skuPrefix();
        return prefix + String.format(AppConstants.SKU_DAILY_NUMBER_FORMAT, itemRepository.findHighestSkuNumber(prefix) + 1);
    }
}
