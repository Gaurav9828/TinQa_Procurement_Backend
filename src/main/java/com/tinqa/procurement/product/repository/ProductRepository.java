package com.tinqa.procurement.product.repository;

import com.tinqa.procurement.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @EntityGraph(attributePaths = {"item"})
    Optional<Product> findWithItemById(Long id);

    @EntityGraph(attributePaths = {"item"})
    Optional<Product> findByProductId(String productId);

    @EntityGraph(attributePaths = {"item"})
    List<Product> findByItemIdOrderByIdAsc(Long itemId);

    @EntityGraph(attributePaths = {"item"})
    Page<Product> findByIsActive(Boolean isActive, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"item"})
    Page<Product> findAll(Pageable pageable);

    boolean existsByProductId(String productId);
}
