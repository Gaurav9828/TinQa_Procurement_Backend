package com.tinqa.procurement.product.service.impl;

import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ConflictException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.common.util.Quantities;
import com.tinqa.procurement.common.util.ReferenceNumberGenerator;
import com.tinqa.procurement.common.validation.OptionalText;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.product.dto.ProductDTOs;
import com.tinqa.procurement.product.entity.Product;
import com.tinqa.procurement.product.entity.ProductStockAllocation;
import com.tinqa.procurement.product.mapper.ProductMapper;
import com.tinqa.procurement.product.repository.ProductRepository;
import com.tinqa.procurement.product.service.ProductService;
import com.tinqa.procurement.security.service.CurrentUserProvider;
import com.tinqa.procurement.stock.entity.Stock;
import com.tinqa.procurement.stock.exception.InsufficientStockException;
import com.tinqa.procurement.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ItemRepository itemRepository;
    private final StockService stockService;
    private final CurrentUserProvider currentUserProvider;
    private final ProductMapper productMapper;
    private final ReferenceNumberGenerator referenceNumbers;

    @Override
    @Transactional
    public ProductDTOs.Response createProduct(ProductDTOs.CreateRequest request) {
        String requestedId = OptionalText.blankToNull(request.getProductId());
        String productId = requestedId != null ? requestedId : referenceNumbers.productId();
        if (productRepository.existsByProductId(productId)) {
            throw new ConflictException("Product ID already exists: " + productId);
        }

        Item item = getActiveItem(request.getItemId());
        Long currentUserId = currentUserProvider.getCurrentUserId();

        Product product = Product.builder()
                .productId(productId)
                .item(item)
                .quantity(request.getQuantity())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();
        consumeStock(product, currentUserId);

        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    @Transactional
    public ProductDTOs.Response updateProduct(Long id, ProductDTOs.UpdateRequest request) {
        Product product = findProduct(id);
        product.setIsActive(request.getIsActive());
        product.setUpdatedBy(currentUserProvider.getCurrentUserId());
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDTOs.Response getProductById(Long id) {
        return productMapper.toResponse(findProduct(id));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDTOs.Response getProductByProductId(String productId) {
        return productRepository.findByProductId(productId)
                .map(productMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with product ID: " + productId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductDTOs.Response> getProductsByItemId(Long itemId) {
        if (!itemRepository.existsById(itemId)) {
            throw itemNotFound(itemId);
        }
        return productRepository.findByItemIdOrderByIdAsc(itemId).stream().map(productMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductDTOs.Response> getProducts(Boolean isActive, Pageable pageable) {
        Page<Product> products = isActive == null
                ? productRepository.findAll(pageable)
                : productRepository.findByIsActive(isActive, pageable);
        return products.map(productMapper::toResponse);
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        Product product = findProduct(id);
        // The stock the product consumed goes back to the entries it came from
        Map<Long, BigDecimal> unitsByStockId = product.getStockAllocations().stream()
                .collect(Collectors.toMap(allocation -> allocation.getStock().getId(), ProductStockAllocation::getQuantity, BigDecimal::add));
        stockService.restoreStock(unitsByStockId, currentUserProvider.getCurrentUserId());
        productRepository.delete(product);
    }

    private void consumeStock(Product product, Long currentUserId) {
        Item item = product.getItem();
        Map<Stock, BigDecimal> consumed;
        try {
            consumed = stockService.consumeItemStock(item, product.getQuantity(), currentUserId);
        } catch (InsufficientStockException exception) {
            throw new InsufficientStockException(insufficientStockMessage(item, exception),
                    exception.getItemId(), exception.getRequestedUnits(), exception.getAvailableUnits());
        }

        consumed.forEach((stock, units) -> product.getStockAllocations().add(ProductStockAllocation.builder()
                .product(product)
                .stock(stock)
                .quantity(units)
                .createdBy(currentUserId)
                .build()));
    }

    private static String insufficientStockMessage(Item item, InsufficientStockException exception) {
        String unit = item.getUnitOfMeasure();
        String itemLabel = "Item '" + item.getName() + "' (SKU: " + item.getSku() + ")";
        String reason = exception.getAvailableUnits().signum() == 0
                ? itemLabel + " is not in stock."
                : itemLabel + " does not have enough stock: requested " + Quantities.plain(exception.getRequestedUnits()) + " "
                + unit + ", only " + Quantities.plain(exception.getAvailableUnits()) + " " + unit + " available.";
        return reason + " Please add or increase the stock for this item, then try again."
                + " The product cannot be created without sufficient stock.";
    }

    private Item getActiveItem(Long itemId) {
        Item item = itemRepository.findById(itemId).orElseThrow(() -> itemNotFound(itemId));
        if (!Boolean.TRUE.equals(item.getIsActive())) {
            throw new BadRequestException("Cannot link a product to an inactive item: " + itemId);
        }
        return item;
    }

    private Product findProduct(Long id) {
        return productRepository.findWithItemById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));
    }

    private static ResourceNotFoundException itemNotFound(Long itemId) {
        return new ResourceNotFoundException("Item not found with ID: " + itemId);
    }
}
