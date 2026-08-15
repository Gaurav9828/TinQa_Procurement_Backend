package com.tinqa.procurement.item.repository;

import com.tinqa.procurement.item.entity.ItemCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemCategoryRepository extends JpaRepository<ItemCategory, Long> {
    boolean existsByCode(String code);
    boolean existsByName(String name);
}