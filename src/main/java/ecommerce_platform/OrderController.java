package ecommerce_platform;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @PostMapping("/buy/{productId}")
    public String buy(@PathVariable Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));

        if (product.getStockQuantity() <= 0) {
            return "OUT OF STOCK";
        }

        // Simulate some processing delay (e.g, checking payment, etc...)
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        product.setStockQuantity(product.getStockQuantity() - 1);
        productRepository.save(product);

        Order order = new Order();
        order.setProductId(productId);
        order.setQuantity(1);
        order.setStatus("CONFIRMED");
        orderRepository.save(order);

        return "ORDER CONFIRMED";
    }
}
