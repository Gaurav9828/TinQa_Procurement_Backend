package com.tinqa.procurement.product.service;

import com.tinqa.procurement.product.dto.ProductDTOs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Products built from an item: creating one consumes the item's stock, deleting it returns that stock.
 */
public interface ProductService {

    ProductDTOs.Response createProduct(ProductDTOs.CreateRequest request);

    ProductDTOs.Response updateProduct(Long id, ProductDTOs.UpdateRequest request);

    ProductDTOs.Response getProductById(Long id);

    ProductDTOs.Response getProductByProductId(String productId);

    List<ProductDTOs.Response> getProductsByItemId(Long itemId);

    Page<ProductDTOs.Response> getProducts(Boolean isActive, Pageable pageable);

    void deleteProduct(Long id);
}
