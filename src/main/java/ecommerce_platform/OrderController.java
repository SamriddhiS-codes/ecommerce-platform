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

    private static final long RESERVATION_TTL_SECONDS = 300;

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
                order.setStatus("RESERVED");
                order = orderRepository.save(order);
                Long orderId = order.getId();

                String reservationKey = "reservation:order:" + orderId;
                redisTemplate.opsForValue().set(reservationKey, String.valueOf(productId), RESERVATION_TTL_SECONDS, TimeUnit.SECONDS);

                result = "ORDER RESERVED - order id " + orderId + " - complete payment within " + RESERVATION_TTL_SECONDS + " seconds";
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

    @PostMapping("/confirm-payment/{orderId}")
    public String confirmPayment(@PathVariable Long orderId) {

        String reservationKey = "reservation:order:" + orderId;
        String productIdStr = redisTemplate.opsForValue().get(reservationKey);

        if (productIdStr == null) {
            return "RESERVATION EXPIRED OR NOT FOUND - stock has been released";
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        order.setStatus("CONFIRMED");
        orderRepository.save(order);

        redisTemplate.delete(reservationKey);

        return "PAYMENT CONFIRMED - order " + orderId + " finalized";
    }

    @PostMapping("/fail-payment/{orderId}")
    public String failPayment(@PathVariable Long orderId) {

        String reservationKey = "reservation:order:" + orderId;
        String productIdStr = redisTemplate.opsForValue().get(reservationKey);

        if (productIdStr == null) {
            return "RESERVATION ALREADY EXPIRED OR NOT FOUND - nothing to roll back";
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if (!"RESERVED".equals(order.getStatus())) {
            return "ORDER IS NOT IN A RESERVED STATE - cannot fail payment on it";
        }

        Long productId = Long.parseLong(productIdStr);

        String lockKey = "lock:product:" + productId;
        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, String.valueOf(System.currentTimeMillis()), 5, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(lockAcquired)) {
            return "SYSTEM BUSY, TRY AGAIN";
        }

        try {
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new RuntimeException("Product not found"));

            product.setStockQuantity(product.getStockQuantity() + 1);
            productRepository.save(product);

            order.setStatus("PAYMENT_FAILED");
            orderRepository.save(order);

            redisTemplate.delete(reservationKey);

            return "PAYMENT FAILED - stock rolled back for order " + orderId;

        } finally {
            redisTemplate.delete(lockKey);
        }
    }
}