package com.tinqa.procurement.product.mapper;

import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.mapper.ItemMapper;
import com.tinqa.procurement.product.dto.ProductDTOs;
import com.tinqa.procurement.product.entity.Product;
import com.tinqa.procurement.product.entity.ProductStockAllocation;
import com.tinqa.procurement.stock.entity.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductMapper {

    private final ItemMapper itemMapper;

    public ProductDTOs.Response toResponse(Product product) {
        Item item = product.getItem();
        ProductDTOs.Response response = new ProductDTOs.Response();
        response.setId(product.getId());
        response.setProductId(product.getProductId());
        response.setItemId(item.getId());
        response.setItemName(item.getName());
        response.setItemSku(item.getSku());
        response.setUnitOfMeasure(item.getUnitOfMeasure());
        response.setQuantity(product.getQuantity());
        response.setStockAllocations(product.getStockAllocations().stream().map(this::toAllocationResponse).toList());
        response.setItemWarranties(itemMapper.toWarrantyResponses(item.getWarranties()));
        response.setIsActive(product.getIsActive());
        response.setCreatedAt(product.getCreatedAt());
        response.setUpdatedAt(product.getUpdatedAt());
        response.setCreatedBy(product.getCreatedBy());
        response.setUpdatedBy(product.getUpdatedBy());
        return response;
    }

    private ProductDTOs.StockAllocationResponse toAllocationResponse(ProductStockAllocation allocation) {
        Stock stock = allocation.getStock();
        ProductDTOs.StockAllocationResponse response = new ProductDTOs.StockAllocationResponse();
        response.setStockId(stock.getId());
        response.setStockIdentityNumber(stock.getStockIdentityNumber());
        response.setBatchNumber(stock.getBatchNumber());
        response.setQuantity(allocation.getQuantity());
        return response;
    }
}
