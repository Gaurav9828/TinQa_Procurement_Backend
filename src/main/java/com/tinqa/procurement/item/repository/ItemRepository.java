package com.tinqa.procurement.item.repository;

import com.tinqa.procurement.item.entity.Item;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {

    @EntityGraph(attributePaths = {"category"})
    @Query("SELECT i FROM Item i WHERE i.id = :id")
    Optional<Item> findByIdWithDetails(@Param("id") Long id);

    boolean existsBySku(String sku);

    Page<Item> findByIsActiveTrue(Pageable pageable);
}