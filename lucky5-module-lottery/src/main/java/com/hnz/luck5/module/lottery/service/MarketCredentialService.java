package com.hnz.luck5.module.lottery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Compatible with the AES-256-GCM credential format used by the original Lucky5 service.
 */
@Service
public class MarketCredentialService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${MARKET_CREDENTIAL_KEY:}")
    private String secret;

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return "";
        requireSecret();
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encryptedWithTag = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            int encryptedLength = encryptedWithTag.length - 16;
            byte[] encrypted = java.util.Arrays.copyOf(encryptedWithTag, encryptedLength);
            byte[] tag = java.util.Arrays.copyOfRange(encryptedWithTag, encryptedLength, encryptedWithTag.length);
            Base64.Encoder encoder = Base64.getEncoder();
            return "v1:" + encoder.encodeToString(iv) + ":" + encoder.encodeToString(tag) + ":"
                    + encoder.encodeToString(encrypted);
        } catch (Exception ex) {
            throw new IllegalStateException("盘口凭据加密失败", ex);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return "";
        requireSecret();
        String[] parts = value.split(":", -1);
        if (parts.length != 4 || !"v1".equals(parts[0])) throw new IllegalStateException("盘口凭据格式无效");
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(parts[1]);
            byte[] tag = decoder.decode(parts[2]);
            byte[] encrypted = decoder.decode(parts[3]);
            if (iv.length != 12 || tag.length != 16) throw new IllegalArgumentException("invalid length");
            byte[] encryptedWithTag = new byte[encrypted.length + tag.length];
            System.arraycopy(encrypted, 0, encryptedWithTag, 0, encrypted.length);
            System.arraycopy(tag, 0, encryptedWithTag, encrypted.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encryptedWithTag), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("盘口凭据无法解密", ex);
        }
    }

    private SecretKeySpec key() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private void requireSecret() {
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("MARKET_CREDENTIAL_KEY 未配置或长度不足");
        }
    }
}
