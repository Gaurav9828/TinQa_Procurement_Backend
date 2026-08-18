package com.tinqa.procurement.stock.repository;

import com.tinqa.procurement.common.constant.ApprovalStatus;
import com.tinqa.procurement.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByStockIdentityNumber(String stockIdentityNumber);
    List<Stock> findByApprovalStatus(ApprovalStatus approvalStatus);
    List<Stock> findByOrderOrderNumber(String orderNumber);
}