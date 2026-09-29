package com.trekmate.exe.controller;

import com.trekmate.exe.dto.request.ChallengeRequest;
import com.trekmate.exe.dto.request.RegisterDeviceRequest;
import com.trekmate.exe.dto.request.TokenRequest;
import com.trekmate.exe.dto.response.ChallengeResponse;
import com.trekmate.exe.dto.response.TokenResponse;
import com.trekmate.exe.model.DevicePublicKey;
import com.trekmate.exe.repository.DevicePublicKeyRepository;
import com.trekmate.exe.security.JwtTokenProvider;
import com.trekmate.exe.security.NonceManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/exe/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "TrekMate Device Authentication", description = "Zero-Trust ECDSA Challenge-Response Authentication APIs")
public class AuthController {

    private final DevicePublicKeyRepository keyRepo;
    private final NonceManager nonceManager;
    private final JwtTokenProvider tokenProvider;

    @PostMapping("/register-device")
    @Operation(summary = "Register device public key")
    public ResponseEntity<?> registerDevice(@Valid @RequestBody RegisterDeviceRequest req) {
        if (keyRepo.existsById(req.userId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "userId already registered with a public key"));
        }
        try {
            DevicePublicKey key = DevicePublicKey.builder()
                    .userId(req.userId())
                    .publicKey(req.publicKey())
                    .build();
            keyRepo.save(key);
            log.info("Device registered: userId={}", req.userId());
            return ResponseEntity.ok(Map.of("message", "Device registered successfully"));
        } catch (DataIntegrityViolationException e) {
            log.warn("Race condition in registerDevice for userId={}", req.userId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "userId already registered with a public key"));
        }
    }

    @PostMapping("/challenge")
    @Operation(summary = "Request a 32-byte cryptographic challenge nonce")
    public ResponseEntity<ChallengeResponse> createChallenge(@Valid @RequestBody ChallengeRequest req) {
        if (!keyRepo.existsById(req.userId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not registered for userId: " + req.userId());
        }
        String nonce = nonceManager.generateNonce(req.userId());
        return ResponseEntity.ok(new ChallengeResponse(req.userId(), nonce));
    }

    @PostMapping("/token")
    @Operation(summary = "Verify signature on challenge nonce and issue short-lived JWT token")
    public ResponseEntity<TokenResponse> generateToken(@Valid @RequestBody TokenRequest req) {
        // 1. Verify and consume one-time nonce
        if (!nonceManager.verifyAndRemoveNonce(req.userId(), req.nonce())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired nonce");
        }

        // 2. Load stored public key
        DevicePublicKey deviceKey = keyRepo.findById(req.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not registered"));

        // 3. Verify SHA256withECDSA signature over nonce
        boolean isValid = verifyEccSignature(deviceKey.getPublicKey(), req.nonce(), req.signature());
        if (!isValid) {
            log.warn("Signature verification failed for userId={}", req.userId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid ECDSA signature");
        }

        // 4. Issue short-lived JWT (14,400 seconds = 4 hours)
        String token = tokenProvider.generateToken(req.userId());
        log.info("JWT token issued successfully for userId={}", req.userId());
        return ResponseEntity.ok(new TokenResponse(token, 14400));
    }

    private boolean verifyEccSignature(String base64PublicKey, String data, String base64Signature) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(publicKey);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(base64Signature));
        } catch (Exception e) {
            log.error("Error during ECDSA signature verification: {}", e.getMessage());
            return false;
        }
    }
}
