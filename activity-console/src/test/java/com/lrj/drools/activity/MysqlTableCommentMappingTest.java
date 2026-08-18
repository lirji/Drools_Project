package com.lrj.drools.activity;

import jakarta.persistence.Entity;
import org.hibernate.annotations.Comment;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlTableCommentMappingTest {

    private static final Set<String> ENTITY_PACKAGES = Set.of(
            "com.lrj.drools.activity.persistence",
            "com.lrj.drools.persistence"
    );

    @Test
    void everyMysqlEntityHasANonBlankTableComment() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Set<Class<?>> entityTypes = new LinkedHashSet<>();
        for (String entityPackage : ENTITY_PACKAGES) {
            scanner.findCandidateComponents(entityPackage).forEach(candidate -> {
                try {
                    entityTypes.add(Class.forName(candidate.getBeanClassName()));
                } catch (ClassNotFoundException exception) {
                    throw new IllegalStateException("无法加载实体类 " + candidate.getBeanClassName(), exception);
                }
            });
        }

        assertThat(entityTypes).isNotEmpty();
        for (Class<?> entityType : entityTypes) {
            Comment comment = entityType.getAnnotation(Comment.class);
            assertThat(comment)
                    .as("实体 %s 缺少 MySQL 表注释", entityType.getName())
                    .isNotNull();
            assertThat(comment.value())
                    .as("实体 %s 的 MySQL 表注释不能为空", entityType.getName())
                    .isNotBlank();
        }
    }
}
