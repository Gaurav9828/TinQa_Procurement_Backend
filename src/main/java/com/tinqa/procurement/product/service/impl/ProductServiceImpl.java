package com.tinqa.procurement.product.service.impl;

import com.tinqa.procurement.common.exception.BadRequestException;
import com.tinqa.procurement.common.exception.ConflictException;
import com.tinqa.procurement.common.exception.ResourceNotFoundException;
import com.tinqa.procurement.item.dto.ItemDTOs;
import com.tinqa.procurement.item.entity.Item;
import com.tinqa.procurement.item.repository.ItemRepository;
import com.tinqa.procurement.product.dto.ProductDTOs;
import com.tinqa.procurement.product.entity.Product;
import com.tinqa.procurement.product.entity.ProductStockAllocation;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ItemRepository itemRepository;
    private final StockService stockService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public ProductDTOs.Response createProduct(ProductDTOs.CreateRequest request) {
        String productId = request.getProductId() == null || request.getProductId().isBlank()
                ? generateProductId()
                : request.getProductId().trim();

        if (productRepository.existsByProductId(productId)) {
            throw new ConflictException("Product ID already exists: " + productId);
        }

        Item item = getActiveItem(request.getItemId());
        Long currentUserId = currentUserProvider.getCurrentUser().getId();

        Product product = Product.builder()
                .productId(productId)
                .item(item)
                .quantity(request.getQuantity())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        consumeStock(product, currentUserId);

        return mapToProductResponse(productRepository.save(product));
    }

    @Override
    @Transactional
    public ProductDTOs.Response updateProduct(Long id, ProductDTOs.UpdateRequest request) {
        Product product = productRepository.findWithItemById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));

        product.setIsActive(request.getIsActive());
        product.setUpdatedBy(currentUserProvider.getCurrentUser().getId());

        return mapToProductResponse(productRepository.save(product));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDTOs.Response getProductById(Long id) {
        Product product = productRepository.findWithItemById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));
        return mapToProductResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDTOs.Response getProductByProductId(String productId) {
        Product product = productRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with product ID: " + productId));
        return mapToProductResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductDTOs.Response> getProductsByItemId(Long itemId) {
        if (!itemRepository.existsById(itemId)) {
            throw new ResourceNotFoundException("Item not found with ID: " + itemId);
        }
        return productRepository.findByItemIdOrderByIdAsc(itemId).stream()
                .map(this::mapToProductResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductDTOs.Response> getProducts(Boolean isActive, Pageable pageable) {
        Page<Product> products = isActive == null
                ? productRepository.findAll(pageable)
                : productRepository.findByIsActive(isActive, pageable);
        return products.map(this::mapToProductResponse);
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findWithItemById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));

        Map<Long, BigDecimal> unitsByStockId = product.getStockAllocations().stream()
                .collect(Collectors.toMap(allocation -> allocation.getStock().getId(),
                        ProductStockAllocation::getQuantity, BigDecimal::add));

        stockService.restoreStock(unitsByStockId, currentUserProvider.getCurrentUser().getId());
        productRepository.delete(product);
    }

    private void consumeStock(Product product, Long currentUserId) {
        Item item = product.getItem();
        Map<Stock, BigDecimal> consumed;
        try {
            consumed = stockService.consumeItemStock(item, product.getQuantity(), currentUserId);
        } catch (InsufficientStockException exception) {
            throw new InsufficientStockException(buildInsufficientStockMessage(item, exception),
                    exception.getItemId(), exception.getRequestedUnits(), exception.getAvailableUnits());
        }

        consumed.forEach((stock, units) -> product.getStockAllocations().add(ProductStockAllocation.builder()
                .product(product)
                .stock(stock)
                .quantity(units)
                .createdBy(currentUserId)
                .build()));
    }

    private String buildInsufficientStockMessage(Item item, InsufficientStockException exception) {
        String unit = item.getUnitOfMeasure();
        String requested = exception.getRequestedUnits().stripTrailingZeros().toPlainString();
        String available = exception.getAvailableUnits().stripTrailingZeros().toPlainString();

        String reason = exception.getAvailableUnits().signum() == 0
                ? "Item '" + item.getName() + "' (SKU: " + item.getSku() + ") is not in stock."
                : "Item '" + item.getName() + "' (SKU: " + item.getSku() + ") does not have enough stock: requested "
                + requested + " " + unit + ", only " + available + " " + unit + " available.";

        return reason + " Please add or increase the stock for this item, then try again."
                + " The product cannot be created without sufficient stock.";
    }

    private Item getActiveItem(Long itemId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + itemId));
        if (!Boolean.TRUE.equals(item.getIsActive())) {
            throw new BadRequestException("Cannot link a product to an inactive item: " + itemId);
        }
        return item;
    }

    private String generateProductId() {
        return "PRD-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    private ProductDTOs.Response mapToProductResponse(Product product) {
        Item item = product.getItem();
        ProductDTOs.Response res = new ProductDTOs.Response();
        res.setId(product.getId());
        res.setProductId(product.getProductId());
        res.setItemId(item.getId());
        res.setItemName(item.getName());
        res.setItemSku(item.getSku());
        res.setUnitOfMeasure(item.getUnitOfMeasure());
        res.setQuantity(product.getQuantity());
        res.setStockAllocations(product.getStockAllocations().stream()
                .map(this::mapToStockAllocationResponse)
                .collect(Collectors.toList()));
        res.setItemWarranties(item.getWarranties().stream()
                .map(ItemDTOs.WarrantyResponse::from)
                .collect(Collectors.toList()));
        res.setIsActive(product.getIsActive());
        res.setCreatedAt(product.getCreatedAt());
        res.setUpdatedAt(product.getUpdatedAt());
        res.setCreatedBy(product.getCreatedBy());
        res.setUpdatedBy(product.getUpdatedBy());
        return res;
    }

    private ProductDTOs.StockAllocationResponse mapToStockAllocationResponse(ProductStockAllocation allocation) {
        Stock stock = allocation.getStock();
        ProductDTOs.StockAllocationResponse res = new ProductDTOs.StockAllocationResponse();
        res.setStockId(stock.getId());
        res.setStockIdentityNumber(stock.getStockIdentityNumber());
        res.setBatchNumber(stock.getBatchNumber());
        res.setQuantity(allocation.getQuantity());
        return res;
    }
}
