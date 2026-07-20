package com.lrj.drools.service;

import com.lrj.drools.domain.LoyaltyState;
import com.lrj.drools.domain.PurchaseEvent;
import com.lrj.drools.persistence.SessionSnapshot;
import com.lrj.drools.persistence.SessionSnapshotRepository;
import org.kie.api.KieBase;
import org.kie.api.marshalling.Marshaller;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
// MarshallerFactory 在 Drools 8 移到 internal 包 (org.kie.internal.marshalling),
// 不是 org.kie.api.marshalling — 这是从 Drools 7 升 8 常踩的导入坑。
import org.kie.internal.marshalling.MarshallerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Step 10: KieSession ↔ byte[] ↔ JPA 实体 三态切换核心。
 *
 * 生命周期:
 *   start(id):     新建 session → insert LoyaltyState → marshall → save snapshot
 *   purchase(id):  load snapshot → unmarshall → insert PurchaseEvent → fire → marshall → save
 *   peek(id):      load snapshot → unmarshall → 读 LoyaltyState (不 fire) → dispose
 *
 * 关键 Drools API:
 *   - MarshallerFactory.newMarshaller(kieBase) 拿到 marshaller (kieBase 决定怎么编解码 fact 类型)
 *   - marshaller.marshall(OutputStream, KieSession) 把整个 working memory + agenda 写出
 *   - marshaller.unmarshall(InputStream) 返回**新的** KieSession 实例
 *     (不是把状态注入到现有 session, 是构造一个新 session 把状态填进去)
 *
 * 关键设计选择:
 *   - 每个操作完都 dispose 老 session, 下次完全从 byte[] 重建 — 简单但每次 unmarshall 有
 *     反序列化开销; 生产场景可加内存缓存 (session id → 活 session) 并在事务里 marshall。
 *   - kieBase 从 kieContainer.getKieBase("loyaltyKBase") 拿, marshaller 和当时 marshall
 *     的 kieBase 必须类型 兼容 (规则签名变了就不能复用旧 byte[])。改 DRL 的话老快照可能 unmarshall 失败,
 *     这是热升级要单独考虑的点; 学习 demo 里我们改完规则手动清 data 目录。
 */
@Service
public class LoyaltyService {

    private final KieContainer kieContainer;
    private final SessionSnapshotRepository repository;

    public LoyaltyService(KieContainer kieContainer, SessionSnapshotRepository repository) {
        this.kieContainer = kieContainer;
        this.repository = repository;
    }

    /** 新建一个会话, 注入空 LoyaltyState, 立即 marshall 落地。同名 sessionId 会被覆盖。 */
    @Transactional
    public LoyaltyState start(String sessionId) {
        KieSession session = kieContainer.newKieSession("loyaltySession");
        try {
            LoyaltyState state = new LoyaltyState();
            session.insert(state);
            snapshot(sessionId, session);
            return state;
        } finally {
            session.dispose();
        }
    }

    /** 恢复会话, 插入一笔购买事件, fire 触发积分累积 + 可能的升级, 再 marshall 落地。 */
    @Transactional
    public LoyaltyState purchase(String sessionId, double amount) {
        KieSession session = restore(sessionId);
        try {
            session.insert(new PurchaseEvent(amount));
            session.fireAllRules();
            snapshot(sessionId, session);
            return extractState(session);
        } finally {
            session.dispose();
        }
    }

    /** 只读: 恢复会话读 LoyaltyState 然后立即丢弃; 不 fire、不写回。 */
    @Transactional(readOnly = true)
    public Optional<LoyaltyState> peek(String sessionId) {
        if (repository.findById(sessionId).isEmpty()) {
            return Optional.empty();
        }
        KieSession session = restore(sessionId);
        try {
            return Optional.of(extractState(session));
        } finally {
            session.dispose();
        }
    }

    private KieSession restore(String sessionId) {
        SessionSnapshot snap = repository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("未知 sessionId: " + sessionId));
        try {
            Marshaller marshaller = marshaller();
            return marshaller.unmarshall(new ByteArrayInputStream(snap.getData()));
        } catch (IOException | ClassNotFoundException e) {
            // 通常发生在: DRL 改了 / fact 类字段变了, 老 byte[] 反序列化失败
            throw new IllegalStateException(
                    "反序列化 session 失败 (规则或 fact 类是否变更?): " + sessionId, e);
        }
    }

    private void snapshot(String sessionId, KieSession session) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            marshaller().marshall(baos, session);
            byte[] data = baos.toByteArray();

            SessionSnapshot snap = repository.findById(sessionId)
                    .orElseGet(() -> new SessionSnapshot(sessionId, data, Instant.now()));
            snap.setData(data);
            snap.setUpdatedAt(Instant.now());
            repository.save(snap);
        } catch (IOException e) {
            throw new IllegalStateException("marshall session 失败: " + sessionId, e);
        }
    }

    private Marshaller marshaller() {
        KieBase kieBase = kieContainer.getKieBase("loyaltyKBase");
        return MarshallerFactory.newMarshaller(kieBase);
    }

    private LoyaltyState extractState(KieSession session) {
        return session.getObjects().stream()
                .filter(LoyaltyState.class::isInstance)
                .map(LoyaltyState.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "session 里没有 LoyaltyState fact — 数据被破坏?"));
    }
}
