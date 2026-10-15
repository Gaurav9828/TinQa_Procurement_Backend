package com.tinqa.procurement.category.repository;

import com.tinqa.procurement.category.entity.Category;
import com.tinqa.procurement.category.enums.CategoryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByType(CategoryType type);

    boolean existsByTypeAndCode(CategoryType type, String code);

    Optional<Category> findByIdAndType(Long id, CategoryType type);

    List<Category> findByIdInAndType(Collection<Long> ids, CategoryType type);
}
