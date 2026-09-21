package com.bbb.exercise.agentdemo1_0.auth;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Per-user provider keys stored in the database for the current local-development setup. */
@Service
public class UserApiKeyService {
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final ApiKeyCrypto crypto;

    public UserApiKeyService(JdbcTemplate jdbc, AuthService auth, ApiKeyCrypto crypto) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.crypto = crypto;
    }

    public void save(ChatIdentity identity, String qwen, String tavily) {
        long userId = auth.requireUserId(identity);
        if (blank(qwen) && blank(tavily)) throw new IllegalArgumentException("至少填写一个 API 密钥");
        String oldQwen = null, oldTavily = null;
        var existing = jdbc.query("SELECT qwen_api_key_ciphertext,tavily_api_key_ciphertext FROM user_api_key WHERE user_id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, userId);
        if (!existing.isEmpty()) { oldQwen = existing.get(0)[0]; oldTavily = existing.get(0)[1]; }
        String qwenCipher = blank(qwen) ? oldQwen : crypto.encrypt(qwen.trim());
        String tavilyCipher = blank(tavily) ? oldTavily : crypto.encrypt(tavily.trim());
        jdbc.update("INSERT INTO user_api_key(user_id,qwen_api_key_ciphertext,tavily_api_key_ciphertext,updated_at) VALUES (?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE qwen_api_key_ciphertext=VALUES(qwen_api_key_ciphertext), "
                        + "tavily_api_key_ciphertext=VALUES(tavily_api_key_ciphertext),updated_at=VALUES(updated_at)",
                userId, qwenCipher, tavilyCipher, LocalDateTime.now());
    }

    public KeyStatus status(ChatIdentity identity) {
        long userId = auth.requireUserId(identity);
        var rows = jdbc.query("SELECT qwen_api_key_ciphertext,tavily_api_key_ciphertext FROM user_api_key WHERE user_id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, userId);
        if (rows.isEmpty()) return new KeyStatus(false, false, null, null);
        return new KeyStatus(!blank(rows.get(0)[0]), !blank(rows.get(0)[1]),
                mask(rows.get(0)[0]), mask(rows.get(0)[1]));
    }

    public UserApiKeys get(ChatIdentity identity) {
        long userId = auth.requireUserId(identity);
        var rows = jdbc.query("SELECT qwen_api_key_ciphertext,tavily_api_key_ciphertext FROM user_api_key WHERE user_id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, userId);
        if (rows.isEmpty()) return new UserApiKeys(null, null);
        return new UserApiKeys(crypto.decrypt(rows.get(0)[0]), crypto.decrypt(rows.get(0)[1]));
    }

    public void clear(ChatIdentity identity) {
        jdbc.update("DELETE FROM user_api_key WHERE user_id=?", auth.requireUserId(identity));
    }

    public void clearProvider(ChatIdentity identity, String provider) {
        long userId = auth.requireUserId(identity);
        if ("qwen".equalsIgnoreCase(provider)) {
            jdbc.update("UPDATE user_api_key SET qwen_api_key_ciphertext=NULL,updated_at=? WHERE user_id=?", LocalDateTime.now(), userId);
        } else if ("tavily".equalsIgnoreCase(provider)) {
            jdbc.update("UPDATE user_api_key SET tavily_api_key_ciphertext=NULL,updated_at=? WHERE user_id=?", LocalDateTime.now(), userId);
        } else {
            throw new IllegalArgumentException("不支持的 API Key 类型");
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String mask(String value) {
        if (blank(value)) return null;
        String v = value.trim();
        if (v.length() <= 8) return "••••••••";
        return v.substring(0, Math.min(4, v.length())) + "••••••••" + v.substring(v.length() - 4);
    }
    public record KeyStatus(boolean qwenConfigured, boolean tavilyConfigured,
                            String qwenApiKey, String tavilyApiKey) {}
    public record UserApiKeys(String qwenApiKey, String tavilyApiKey) {
        public boolean hasQwen() { return qwenApiKey != null && !qwenApiKey.isBlank(); }
        public boolean hasTavily() { return tavilyApiKey != null && !tavilyApiKey.isBlank(); }
    }
}
