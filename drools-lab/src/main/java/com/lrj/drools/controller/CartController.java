package com.lrj.drools.controller;

import com.lrj.drools.domain.Cart;
import com.lrj.drools.domain.Customer;
import com.lrj.drools.domain.OrderItem;
import com.lrj.drools.service.CartService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /** Step 3 展示: accumulate (按品类聚合) + modify (动态升金卡)。 */
    @PostMapping("/cart/checkout")
    public Cart checkout(@RequestBody CheckoutRequest req) {
        Cart cart = new Cart(
                req.cartId() != null ? req.cartId() : UUID.randomUUID().toString(),
                req.customer(),
                req.items()
        );
        return cartService.checkout(cart);
    }

    public record CheckoutRequest(String cartId, Customer customer, List<OrderItem> items) {}
}
