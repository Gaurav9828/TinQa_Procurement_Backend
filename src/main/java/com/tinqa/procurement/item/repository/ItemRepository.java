package com.tinqa.procurement.item.repository;

import com.tinqa.procurement.item.entity.Item;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {

    @EntityGraph(attributePaths = {"categories"})
    @Query("SELECT i FROM Item i WHERE i.id = :id")
    Optional<Item> findByIdWithDetails(@Param("id") Long id);

    // Highest running number among the SKUs that start with the prefix, e.g. 12 for TQ-20261028-0012
    @Query(value = "SELECT COALESCE(MAX(CAST(substring(sku FROM char_length(:prefix) + 1) AS INTEGER)), 0) FROM items "
            + "WHERE sku ~ ('^' || :prefix || '[0-9]+$')", nativeQuery = true)
    int findHighestSkuNumber(@Param("prefix") String prefix);

    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended('item-sku', 0)) AS text)", nativeQuery = true)
    String lockSkuSequence();

    Page<Item> findByIsActiveTrue(Pageable pageable);

    List<Item> findByIsActiveTrueOrderByNameAsc();

    /** Active items in at least one of the given categories, by name. */
    @Query("SELECT DISTINCT i FROM Item i JOIN i.categories c "
            + "WHERE i.isActive = true AND c.id IN :categoryIds ORDER BY i.name ASC")
    List<Item> findActiveInCategories(@Param("categoryIds") Collection<Long> categoryIds);
}