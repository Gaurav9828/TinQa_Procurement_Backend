package com.tinqa.procurement.clientdemo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DemoEventRepository extends JpaRepository<DemoEvent, Long> {

    List<DemoEvent> findByDemoIdOrderByIdAsc(Long demoId);
}
