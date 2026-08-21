package com.lrj.drools.activity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "activity.award-intent")
public class AwardIntentConnectorProperties {
    private boolean relayEnabled;
    private String benefitCenterUrl = "http://localhost:8083";
    private String bearerToken;
    private int batchSize = 100;
    private int maxAttempts = 10;
    private long relayIntervalMs = 5000;
    private long leaseMs = 30000;

    public boolean isRelayEnabled() { return relayEnabled; }
    public void setRelayEnabled(boolean relayEnabled) { this.relayEnabled = relayEnabled; }
    public String getBenefitCenterUrl() { return benefitCenterUrl; }
    public void setBenefitCenterUrl(String benefitCenterUrl) { this.benefitCenterUrl = benefitCenterUrl; }
    public String getBearerToken() { return bearerToken; }
    public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRelayIntervalMs() { return relayIntervalMs; }
    public void setRelayIntervalMs(long relayIntervalMs) { this.relayIntervalMs = relayIntervalMs; }
    public long getLeaseMs() { return leaseMs; }
    public void setLeaseMs(long leaseMs) { this.leaseMs = leaseMs; }
}
