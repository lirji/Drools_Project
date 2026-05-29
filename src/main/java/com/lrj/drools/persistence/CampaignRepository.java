package com.lrj.drools.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Step 18: 活动定义的 Spring Data 仓库。主键是业务自带的 campaignId (如 "double11-newuser")。
 */
public interface CampaignRepository extends JpaRepository<CampaignEntity, String> {
}
