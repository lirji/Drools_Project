-- Activity platform -> benefit-center connector (expand-only).
-- Execute before enabling activity.award-intent.mode=SHADOW/CENTER.

CREATE TABLE IF NOT EXISTS activity_award_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  activity_id VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  source_kind VARCHAR(32) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  benefit_sku_id VARCHAR(128) NOT NULL,
  delivery_mode VARCHAR(16) NOT NULL,
  amount_mode VARCHAR(16) NOT NULL,
  item_template_json LONGTEXT,
  created_stime TIMESTAMP(3) NOT NULL,
  modified_stime TIMESTAMP(3) NOT NULL,
  CONSTRAINT uk_award_binding_source UNIQUE
    (tenant_id, activity_id, version, source_kind, source_ref, benefit_sku_id),
  KEY idx_award_binding_activity_version (tenant_id, activity_id, version)
);

CREATE TABLE IF NOT EXISTS activity_award_intent_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id VARCHAR(64) NOT NULL,
  source_system VARCHAR(64) NOT NULL,
  source_request_id VARCHAR(128) NOT NULL,
  activity_id VARCHAR(64) NOT NULL,
  activity_version INT NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  payload LONGTEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempt INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(3) NULL,
  sent_at TIMESTAMP(3) NULL,
  lease_owner VARCHAR(128) NULL,
  lease_until TIMESTAMP(3) NULL,
  created_stime TIMESTAMP(3) NOT NULL,
  modified_stime TIMESTAMP(3) NOT NULL,
  CONSTRAINT uk_award_intent_source UNIQUE (tenant_id, source_system, source_request_id),
  KEY idx_award_intent_outbox_due (tenant_id, status, next_attempt_at, id),
  KEY idx_award_intent_outbox_lease (status, lease_until, tenant_id)
);

-- Existing connector installations are expanded in place before relay rollout.
SET @award_lease_owner_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE activity_award_intent_outbox ADD COLUMN lease_owner VARCHAR(128) NULL',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'activity_award_intent_outbox'
    AND column_name = 'lease_owner'
);
PREPARE award_lease_owner_stmt FROM @award_lease_owner_sql;
EXECUTE award_lease_owner_stmt;
DEALLOCATE PREPARE award_lease_owner_stmt;

SET @award_lease_until_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE activity_award_intent_outbox ADD COLUMN lease_until TIMESTAMP(3) NULL',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'activity_award_intent_outbox'
    AND column_name = 'lease_until'
);
PREPARE award_lease_until_stmt FROM @award_lease_until_sql;
EXECUTE award_lease_until_stmt;
DEALLOCATE PREPARE award_lease_until_stmt;

SET @award_lease_index_sql = (
  SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_award_intent_outbox_lease ON activity_award_intent_outbox (status, lease_until, tenant_id)',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'activity_award_intent_outbox'
    AND index_name = 'idx_award_intent_outbox_lease'
);
PREPARE award_lease_index_stmt FROM @award_lease_index_sql;
EXECUTE award_lease_index_stmt;
DEALLOCATE PREPARE award_lease_index_stmt;
