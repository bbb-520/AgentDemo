package com.bbb.exercise.agentdemo1_0.auth;

import com.bbb.exercise.agentdemo1_0.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM encryption for provider keys stored in MySQL. */
@Component
public class ApiKeyCrypto {
    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyCrypto(AppProperties properties) { this.properties = properties; }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("API 密钥加密失败", e);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String encoded = value.startsWith(PREFIX) ? value.substring(PREFIX.length()) : value;
            byte[] payload = Base64.getDecoder().decode(encoded);
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API 密钥解密失败，请检查 API_KEYS_ENCRYPTION_KEY", e);
        }
    }

    private SecretKeySpec key() {
        String configured = properties.getSecurity().getApiKeysEncryptionKey();
        if (configured == null || configured.length() < 16) {
            throw new IllegalStateException("未配置 API_KEYS_ENCRYPTION_KEY（至少 16 个字符）");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(configured.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 API 密钥加密失败", e);
        }
    }
}
