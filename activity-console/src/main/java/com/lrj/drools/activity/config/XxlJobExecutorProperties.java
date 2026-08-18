package com.lrj.drools.activity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** XXL-JOB 3.4.2 执行器标准配置。 */
@ConfigurationProperties(prefix = "xxl.job")
public class XxlJobExecutorProperties {

    private final Admin admin = new Admin();
    private final Executor executor = new Executor();
    private String accessToken;

    public Admin getAdmin() {
        return admin;
    }

    public Executor getExecutor() {
        return executor;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public static class Admin {
        private String addresses;

        public String getAddresses() {
            return addresses;
        }

        public void setAddresses(String addresses) {
            this.addresses = addresses;
        }
    }

    public static class Executor {
        private String appname = "activity-console";
        private String address;
        private String ip;
        private int port = 9999;
        private String logPath = "./logs/xxl-job/jobhandler";
        private int logRetentionDays = 30;

        public String getAppname() {
            return appname;
        }

        public void setAppname(String appname) {
            this.appname = appname;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getLogPath() {
            return logPath;
        }

        public void setLogPath(String logPath) {
            this.logPath = logPath;
        }

        public int getLogRetentionDays() {
            return logRetentionDays;
        }

        public void setLogRetentionDays(int logRetentionDays) {
            this.logRetentionDays = logRetentionDays;
        }
    }
}
