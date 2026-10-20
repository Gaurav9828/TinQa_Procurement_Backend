package com.tinqa.procurement.dealer.repository;

import com.tinqa.procurement.dealer.entity.Dealer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DealerRepository extends JpaRepository<Dealer, Long>, JpaSpecificationExecutor<Dealer> {

    @EntityGraph(attributePaths = {"categories"})
    @Query("SELECT d FROM Dealer d WHERE d.id = :id")
    Optional<Dealer> findByIdWithCategories(@Param("id") Long id);

    boolean existsByGstin(String gstin);

    @Query("SELECT DISTINCT d.city FROM Dealer d WHERE d.city IS NOT NULL ORDER BY d.city")
    List<String> findDistinctCities();
}