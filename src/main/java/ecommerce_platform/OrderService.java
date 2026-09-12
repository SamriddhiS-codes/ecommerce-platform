package ecommerce_platform;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Transactional
    public Order reserveStock(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));

        if (product.getStockQuantity() <= 0) {
            throw new IllegalStateException("OUT_OF_STOCK");
        }

        product.setStockQuantity(product.getStockQuantity() - 1);
        productRepository.save(product);

        Order order = new Order();
        order.setProductId(productId);
        order.setQuantity(1);
        order.setStatus("RESERVED");
        return orderRepository.save(order);
    }

    @Transactional
    public void rollbackStock(Long productId, Order order) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));

        product.setStockQuantity(product.getStockQuantity() + 1);
        productRepository.save(product);

        order.setStatus("PAYMENT_FAILED");
        orderRepository.save(order);
    }
}
