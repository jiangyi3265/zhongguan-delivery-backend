package com.ruoyi.qicai.payment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpServletRequest;

/** Synthetic keys only. No merchant-supplied credentials or external calls. */
final class PaymentCryptoFixture
{
    final String apiKey = "0123456789abcdef0123456789abcdef";
    final KeyPair platform;
    final KeyPair merchant;
    final WechatPayProperties properties = new WechatPayProperties();
    final OfficialWechatPaymentGateway official;
    final ObjectMapper json = new ObjectMapper();
    PaymentCryptoFixture() throws Exception
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        merchant = generator.generateKeyPair(); platform = generator.generateKeyPair();
        Path dir = Files.createTempDirectory("qicai-synthetic-payment-");
        Files.write(dir.resolve("merchant.pem"), pem("PRIVATE KEY",merchant.getPrivate().getEncoded()).getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("platform.pem"), pem("PUBLIC KEY",platform.getPublic().getEncoded()).getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("api.key"), apiKey.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("app.key"), "synthetic-app-secret".getBytes(StandardCharsets.UTF_8));
        properties.setEnabled(true); properties.setMerchantId("1900000000"); properties.setAppId("wx0123456789abcdef");
        properties.setMerchantSerialNumber("123ABC"); properties.setPrivateKeyPath(dir.resolve("merchant.pem").toString());
        properties.setPublicKeyPath(dir.resolve("platform.pem").toString()); properties.setPublicKeyId("PUB_KEY_ID_12345");
        properties.setApiV3KeyFile(dir.resolve("api.key").toString()); properties.setAppSecretFile(dir.resolve("app.key").toString());
        properties.setNotifyUrl("https://example.invalid/qicai/payment/wechat/notify");
        official = new OfficialWechatPaymentGateway(properties,json); official.initialize();
    }
    static String pem(String type,byte[] bytes)
    { return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+type+"-----\n"; }
    Map<String,Object> transaction(String trade,int amount,String openId)
    {
        Map<String,Object> m=new LinkedHashMap<String,Object>();
        m.put("appid",properties.getAppId());m.put("mchid",properties.getMerchantId());m.put("out_trade_no",trade);
        m.put("trade_state","SUCCESS");m.put("trade_type","JSAPI");m.put("transaction_id","420000"+trade);
        m.put("success_time",OffsetDateTime.now().toString());
        Map<String,Object> a=new LinkedHashMap<String,Object>();a.put("total",amount);a.put("currency","CNY");a.put("payer_total",Math.max(1,amount-1));m.put("amount",a);
        Map<String,Object> p=new LinkedHashMap<String,Object>();p.put("openid",openId);m.put("payer",p);return m;
    }
    String envelope(Map<String,Object> transaction) throws Exception
    {
        String nonce="0123456789ab",associated="transaction";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
        Map<String,Object> resource=new LinkedHashMap<String,Object>();resource.put("algorithm","AEAD_AES_256_GCM");resource.put("nonce",nonce);resource.put("associated_data",associated);
        resource.put("ciphertext",Base64.getEncoder().encodeToString(cipher.doFinal(json.writeValueAsBytes(transaction))));
        Map<String,Object> body=new LinkedHashMap<String,Object>();body.put("id",java.util.UUID.randomUUID().toString());body.put("event_type","TRANSACTION.SUCCESS");body.put("resource_type","encrypt-resource");body.put("resource",resource);
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(body)+"\n";
    }
    MockHttpServletRequest signed(String body,long timestamp) throws Exception
    {
        String nonce="synthetic-notification";
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(platform.getPrivate());
        signer.update((timestamp+"\n"+nonce+"\n"+body+"\n").getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Wechatpay-Serial",properties.getPublicKeyId());
        request.addHeader("Wechatpay-Nonce",nonce);request.addHeader("Wechatpay-Timestamp",Long.toString(timestamp));
        request.addHeader("Wechatpay-Signature",Base64.getEncoder().encodeToString(signer.sign()));return request;
    }
    MockHttpServletRequest signed(String body) throws Exception { return signed(body,Instant.now().getEpochSecond()); }
}
