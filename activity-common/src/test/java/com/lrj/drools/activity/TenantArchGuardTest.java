package com.lrj.drools.activity;

import jakarta.persistence.Entity;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-4 隔离机制的**结构守卫**——把"隔离靠机制不靠纪律"钉成编译期/CI 可查的不变量。
 *
 * <p>比"CI 阻断裸 findAll()"更对症：加了 {@code @TenantId} 后 {@code findAll()} 已被引擎自动加谓词、
 * <em>本身安全</em>；真正的footgun 是**新加一张实体表却忘了租户列**，或**写原生 SQL 绕过 @TenantId 过滤**。
 * 本测试就守这两条：
 *   1) {@code persistence} 包下每个 {@code @Entity} 必须有一个 {@code @TenantId} 字段（全局表放 {@link #GLOBAL_ENTITIES} 白名单显式豁免）；
 *   2) 仓库接口不得出现 {@code @Query(nativeQuery = true)}（原生 SQL 不经 @TenantId 过滤 = 隔离漏洞）。
 */
class TenantArchGuardTest {

    private static final String PKG = "com.lrj.drools.activity.persistence";

    /**
     * @TenantId 豁免白名单（有意为之）。
     *
     * <p>{@code ActivityGenerationEntity}（M1.4 发布代际）：**带显式 {@code tenant_id} 列**（每行有租户标签，不泄漏），
     * 但刻意<b>不</b>加 {@code @TenantId}——它是跨租户的发布传播信号，供 decision 侧<b>无请求上下文</b>的后台 poller
     * {@code findAll()} 扫描；若加 @TenantId，后台线程会被自动追加 {@code tenant_id = NO_TENANT} 谓词而恒空。
     * 隔离由 poller 读某租户 ACTIVE artifact 时的 {@code TenantContext.callWith(tenant,…)} 保证，不靠本表自过滤。
     * 详见 {@link com.lrj.drools.activity.persistence.ActivityGenerationEntity} 类注释。
     */
    private static final Set<String> GLOBAL_ENTITIES = Set.of("ActivityGenerationEntity");

    @Test
    void everyEntityHasTenantId() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<BeanDefinition> defs = scanner.findCandidateComponents(PKG);

        assertTrue(defs.size() >= 10, "扫描应至少发现 10 个实体（防扫描为空导致假绿），实得 " + defs.size());

        List<String> missing = new ArrayList<>();
        for (BeanDefinition def : defs) {
            Class<?> clazz = Class.forName(def.getBeanClassName());
            if (GLOBAL_ENTITIES.contains(clazz.getSimpleName())) continue;
            if (!hasTenantIdField(clazz)) missing.add(clazz.getSimpleName());
        }
        assertTrue(missing.isEmpty(),
                "以下 @Entity 缺 @TenantId（隔离漏洞；确为全局表请登记 GLOBAL_ENTITIES）：" + missing);
    }

    /**
     * <b>要沿继承链往上找</b>：租户列现在收在 {@code TenantScopedEntity}（{@code @MappedSuperclass}）里，
     * 只看 {@code getDeclaredFields()} 会把每一个继承来的实体误判成「缺租户列」。
     *
     * <p>这里守的是「这张表的 SQL 会不会被自动加 tenant 谓词」——Hibernate 认继承来的 {@code @TenantId}，
     * 那么本测试也必须认，否则它守的就不再是隔离，而是「字段写在哪个文件里」。
     */
    private static boolean hasTenantIdField(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(TenantId.class)) return true;
            }
        }
        return false;
    }

    @Test
    void noNativeQueryInRepositories() throws Exception {
        // 扫全包下的接口（include 全匹配 + isCandidate 只收接口），加载后用真实 isAssignableFrom 判定仓库，
        // 不依赖 AssignableTypeFilter 对接口继承链的 ASM 遍历（更稳）。
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter((metadataReader, factory) -> true);
        Set<BeanDefinition> defs = scanner.findCandidateComponents(PKG);

        List<Class<?>> repos = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (BeanDefinition def : defs) {
            Class<?> clazz = Class.forName(def.getBeanClassName());
            if (!Repository.class.isAssignableFrom(clazz)) continue;
            repos.add(clazz);
            for (Method m : clazz.getMethods()) {
                Query q = m.getAnnotation(Query.class);
                if (q != null && q.nativeQuery()) {
                    offenders.add(clazz.getSimpleName() + "#" + m.getName());
                }
            }
        }
        assertTrue(repos.size() >= 10, "应至少发现 10 个仓库接口（防扫描为空导致假绿），实得 " + repos.size());
        assertTrue(offenders.isEmpty(),
                "以下仓库方法用了 nativeQuery（绕过 @TenantId 租户过滤）：" + offenders);
    }
}
