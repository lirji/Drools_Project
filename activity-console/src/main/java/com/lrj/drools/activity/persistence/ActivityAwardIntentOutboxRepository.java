package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityAwardIntentOutboxRepository extends JpaRepository<ActivityAwardIntentOutboxEntity, Long> {
    Optional<ActivityAwardIntentOutboxEntity> findFirstBySourceSystemAndSourceRequestId(
            String sourceSystem, String sourceRequestId);

    @Query("""
            select e from ActivityAwardIntentOutboxEntity e
            where e.status = 'PENDING'
               or (e.status = 'FAILED' and (e.nextAttemptAt is null or e.nextAttemptAt <= :now))
               or (e.status = 'SENDING' and (e.leaseUntil is null or e.leaseUntil <= :now))
            order by e.id
            """)
    List<ActivityAwardIntentOutboxEntity> findRetryable(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("""
            update ActivityAwardIntentOutboxEntity e
               set e.status='SENDING',e.leaseOwner=:owner,e.leaseUntil=:leaseUntil,e.modifiedStime=:now
             where e.id=:id and (
                   e.status='PENDING'
                or (e.status='FAILED' and (e.nextAttemptAt is null or e.nextAttemptAt<=:now))
                or (e.status='SENDING' and (e.leaseUntil is null or e.leaseUntil<=:now)))
            """)
    int tryClaim(@Param("id") Long id, @Param("owner") String owner,
                 @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true)
    @Query("""
            update ActivityAwardIntentOutboxEntity e
               set e.status='SENT',e.sentAt=:now,e.leaseOwner=null,e.leaseUntil=null,e.modifiedStime=:now
             where e.id=:id and e.status='SENDING' and e.leaseOwner=:owner
            """)
    int markSentClaimed(@Param("id") Long id, @Param("owner") String owner, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("""
            update ActivityAwardIntentOutboxEntity e
               set e.status='FAILED',e.attempt=e.attempt+1,e.nextAttemptAt=:nextAttemptAt,
                   e.leaseOwner=null,e.leaseUntil=null,e.modifiedStime=:now
             where e.id=:id and e.status='SENDING' and e.leaseOwner=:owner
            """)
    int markFailedClaimed(@Param("id") Long id, @Param("owner") String owner,
                          @Param("now") Instant now, @Param("nextAttemptAt") Instant nextAttemptAt);

    @Modifying(clearAutomatically = true)
    @Query("""
            update ActivityAwardIntentOutboxEntity e
               set e.status='DEAD',e.attempt=e.attempt+1,e.nextAttemptAt=null,
                   e.leaseOwner=null,e.leaseUntil=null,e.modifiedStime=:now
             where e.id=:id and e.status='SENDING' and e.leaseOwner=:owner
            """)
    int markDeadClaimed(@Param("id") Long id, @Param("owner") String owner, @Param("now") Instant now);
}
