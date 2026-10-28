package com.tinqa.procurement.clientdemo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DemoPostingRepository extends JpaRepository<DemoPosting, Long> {

    // Company account feed, in creation order
    @Query("SELECT p FROM DemoPosting p WHERE p.createdAt >= :since ORDER BY p.createdAt ASC, p.id ASC")
    List<DemoPosting> findCreatedSince(@Param("since") Instant since, Pageable pageable);
}
