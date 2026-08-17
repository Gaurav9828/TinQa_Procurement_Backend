package com.tinqa.procurement.dealer.repository;

import com.tinqa.procurement.dealer.entity.Dealer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DealerRepository extends JpaRepository<Dealer, Long>, JpaSpecificationExecutor<Dealer> {

    @EntityGraph(attributePaths = {"categories"})
    @Query("SELECT d FROM Dealer d WHERE d.id = :id")
    Optional<Dealer> findByIdWithCategories(@Param("id") Long id);

    boolean existsByGstin(String gstin);

    boolean existsByEmail(String email);

    Page<Dealer> findByIsActiveTrue(Pageable pageable);
}