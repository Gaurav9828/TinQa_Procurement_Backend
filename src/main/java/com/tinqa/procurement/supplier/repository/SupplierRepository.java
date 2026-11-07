package com.tinqa.procurement.supplier.repository;

import com.tinqa.procurement.supplier.entity.Supplier;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {

    @EntityGraph(attributePaths = {"categories"})
    @Query("SELECT d FROM Supplier d WHERE d.id = :id")
    Optional<Supplier> findByIdWithCategories(@Param("id") Long id);

    boolean existsByGstin(String gstin);

    @Query("SELECT DISTINCT d.city FROM Supplier d WHERE d.city IS NOT NULL ORDER BY d.city")
    List<String> findDistinctCities();
}