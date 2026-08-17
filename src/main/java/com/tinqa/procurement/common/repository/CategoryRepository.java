package com.tinqa.procurement.common.repository;

import com.tinqa.procurement.common.entity.Category;
import com.tinqa.procurement.common.enums.CategoryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByType(CategoryType type);

    List<Category> findByTypeAndIsActiveTrue(CategoryType type);

    boolean existsByTypeAndCode(CategoryType type, String code);

    boolean existsByTypeAndName(CategoryType type, String name);

    Optional<Category> findByIdAndType(Long id, CategoryType type);

    boolean existsByCode(String code);

}