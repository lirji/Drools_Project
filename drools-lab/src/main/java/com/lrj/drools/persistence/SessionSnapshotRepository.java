package com.lrj.drools.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionSnapshotRepository extends JpaRepository<SessionSnapshot, String> {
}
