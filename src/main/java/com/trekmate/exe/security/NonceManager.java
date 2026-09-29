package com.trekmate.exe.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NonceManager {

    private final Map<String, NonceEntry> nonceStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    private record NonceEntry(String nonce, Instant expiresAt) {}

    public String generateNonce(String userId) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String nonce = Base64.getEncoder().encodeToString(randomBytes);

        // Single active challenge per userId, valid for 60 seconds
        nonceStore.put(userId, new NonceEntry(nonce, Instant.now().plusSeconds(60)));
        return nonce;
    }

    public boolean verifyAndRemoveNonce(String userId, String candidateNonce) {
        NonceEntry entry = nonceStore.remove(userId); // One-time use: delete immediately
        if (entry == null) return false;
        if (Instant.now().isAfter(entry.expiresAt())) return false;
        return entry.nonce().equals(candidateNonce);
    }
}
