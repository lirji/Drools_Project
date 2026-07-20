package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductPoolRepository extends JpaRepository<ProductPoolEntity, Long> {

    Optional<ProductPoolEntity> findFirstByIdAndIsDel(Long id, Integer isDel);

    /** 启用中的池（status=1, isDel=0）。 */
    List<ProductPoolEntity> findByStatusAndIsDel(Integer status, Integer isDel);
}
