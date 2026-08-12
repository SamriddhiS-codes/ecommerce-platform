package ecommerce_platform;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @PostMapping("/buy/{productId}")
    public String buy(
            @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey != null) {
            String idempotencyRedisKey = "idempotency:" + idempotencyKey;
            String existingResult = redisTemplate.opsForValue().get(idempotencyRedisKey);

            if (existingResult != null) {
                return existingResult + " (DUPLICATE REQUEST - RETURNED CACHED RESULT)";
            }
        }

        String lockKey = "lock:product:" + productId;
        String lockValue = String.valueOf(System.currentTimeMillis());

        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, 5, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(lockAcquired)) {
            return "SYSTEM BUSY, TRY AGAIN";
        }

        String result;

        try {
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new RuntimeException("Product not found"));

            if (product.getStockQuantity() <= 0) {
                result = "OUT OF STOCK";
            } else {
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

                result = "ORDER CONFIRMED";
            }

        } finally {
            redisTemplate.delete(lockKey);
        }

        if (idempotencyKey != null) {
            String idempotencyRedisKey = "idempotency:" + idempotencyKey;
            redisTemplate.opsForValue().set(idempotencyRedisKey, result, 24, TimeUnit.HOURS);
        }

        return result;
    }
}