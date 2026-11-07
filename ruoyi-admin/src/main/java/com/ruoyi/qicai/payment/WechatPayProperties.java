package com.ruoyi.qicai.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "qicai.wechat-pay")
public class WechatPayProperties
{
    private boolean enabled;
    private String appId = "", appSecretFile = "", merchantId = "", merchantSerialNumber = "";
    private String privateKeyPath = "", publicKeyPath = "", publicKeyId = "", apiV3KeyFile = "", notifyUrl = "";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { appId = v; }
    public String getAppSecretFile() { return appSecretFile; }
    public void setAppSecretFile(String v) { appSecretFile = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { merchantId = v; }
    public String getMerchantSerialNumber() { return merchantSerialNumber; }
    public void setMerchantSerialNumber(String v) { merchantSerialNumber = v; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String v) { privateKeyPath = v; }
    public String getPublicKeyPath() { return publicKeyPath; }
    public void setPublicKeyPath(String v) { publicKeyPath = v; }
    public String getPublicKeyId() { return publicKeyId; }
    public void setPublicKeyId(String v) { publicKeyId = v; }
    public String getApiV3KeyFile() { return apiV3KeyFile; }
    public void setApiV3KeyFile(String v) { apiV3KeyFile = v; }
    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String v) { notifyUrl = v; }
}
