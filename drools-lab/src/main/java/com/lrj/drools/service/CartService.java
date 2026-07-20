package com.lrj.drools.service;

import com.lrj.drools.domain.Cart;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

@Service
public class CartService {

    private final KieContainer kieContainer;

    public CartService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    /**
     * 跑 cartSession。注意只 insert 了 Cart, 没 insert Customer / OrderItem,
     * 因为本 KBase 的规则 LHS 不直接 match Customer/OrderItem 类型 —
     * accumulate 是从 $cart.getItems() 这个 Java 集合迭代的。
     */
    public Cart checkout(Cart cart) {
        KieSession session = kieContainer.newKieSession("cartSession");
        try {
            session.insert(cart);
            int firedCount = session.fireAllRules();
            System.out.println("[CartService] 触发了 " + firedCount + " 条规则");
            return cart;
        } finally {
            session.dispose();
        }
    }
}
