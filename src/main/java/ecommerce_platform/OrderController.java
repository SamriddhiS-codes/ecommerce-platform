package ecommerce_platform;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final long RESERVATION_TTL_SECONDS = 300;
    private static final int MAX_REQUESTS_PER_WINDOW = 5;
    private static final int RATE_LIMIT_WINDOW_SECONDS = 10;

    private boolean isRateLimited(String clientId) {
        String rateLimitKey = "ratelimit:" + clientId;

        Long currentCount = redisTemplate.opsForValue().increment(rateLimitKey);

        if (currentCount != null && currentCount == 1L) {
            redisTemplate.expire(rateLimitKey, RATE_LIMIT_WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        return currentCount != null && currentCount > MAX_REQUESTS_PER_WINDOW;
    }

    @PostMapping("/buy/{productId}")
    public String buy(
            @PathVariable Long productId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        String clientIp = request.getRemoteAddr();
        if (isRateLimited(clientIp)) {
            return "TOO MANY REQUESTS - please slow down";
        }

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
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            try {
                Order order = orderService.reserveStock(productId);

                String reservationKey = "reservation:order:" + order.getId();
                redisTemplate.opsForValue().set(reservationKey, String.valueOf(productId), RESERVATION_TTL_SECONDS, TimeUnit.SECONDS);

                result = "ORDER RESERVED - order id " + order.getId() + " - complete payment within " + RESERVATION_TTL_SECONDS + " seconds";

            } catch (IllegalStateException e) {
                result = "OUT OF STOCK";
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
            orderService.rollbackStock(productId, order);
            redisTemplate.delete(reservationKey);
            return "PAYMENT FAILED - stock rolled back for order " + orderId;

        } finally {
            redisTemplate.delete(lockKey);
        }
    }
}