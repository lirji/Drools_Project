package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DemoProductRepository extends JpaRepository<DemoProductEntity, Long> {

    List<DemoProductEntity> findByOnShelf(Integer onShelf);
}
