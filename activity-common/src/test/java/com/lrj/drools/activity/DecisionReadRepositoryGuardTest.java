package com.lrj.drools.activity;

import com.lrj.drools.activity.service.DecisionDataLoader;
import com.lrj.drools.activity.snapshot.DecisionSnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R17 的**结构守卫**：决策取数层在<b>类型上</b>写不了库。
 *
 * <p>此前「decision 进程不写库」只靠运行期兜住——只读数据库账号 + {@code ddl-auto: validate}。
 * 读路径上一次手滑的 {@code save(...)} 能编译、能过全部单测（测试库是可写的 H2），
 * 只在生产那条只读连接上炸。本测试把它钉成结构不变量：
 * <ol>
 *   <li>{@code *ReadRepository} 一律继承 {@link Repository} 而<b>不是</b> {@link CrudRepository}
 *       （后者带 {@code save} / {@code delete} / {@code deleteAll}）；</li>
 *   <li>{@link DecisionDataLoader} 与 {@link DecisionSnapshotBuilder} 的仓库字段
 *       一个都不能是 {@code CrudRepository} 的子类型——否则第 1 条守住了、注入换回可写仓库照样绕过。</li>
 * </ol>
 */
class DecisionReadRepositoryGuardTest {

    private static final String PKG = "com.lrj.drools.activity.persistence";

    @Test
    void readRepositoriesDoNotExposeWrites() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter((metadataReader, factory) -> true);
        Set<BeanDefinition> defs = scanner.findCandidateComponents(PKG);

        List<String> found = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (BeanDefinition def : defs) {
            Class<?> clazz = Class.forName(def.getBeanClassName());
            if (!clazz.getSimpleName().endsWith("ReadRepository")) continue;
            found.add(clazz.getSimpleName());
            if (CrudRepository.class.isAssignableFrom(clazz)) offenders.add(clazz.getSimpleName());
        }
        assertTrue(found.size() >= 6, "应至少发现 6 个只读仓库（防扫描为空导致假绿），实得 " + found);
        assertTrue(offenders.isEmpty(),
                "以下只读仓库继承了 CrudRepository/JpaRepository，save/delete 又回到类型上了：" + offenders);
    }

    @Test
    void decisionReadPathHoldsNoWritableRepository() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> owner : List.of(DecisionDataLoader.class, DecisionSnapshotBuilder.class)) {
            for (Field f : owner.getDeclaredFields()) {
                if (!Repository.class.isAssignableFrom(f.getType())) continue;
                if (CrudRepository.class.isAssignableFrom(f.getType())) {
                    offenders.add(owner.getSimpleName() + "#" + f.getName()
                            + "(" + f.getType().getSimpleName() + ")");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "决策取数层持有了可写仓库（只读保证退回运行期）：" + offenders);
    }
}
