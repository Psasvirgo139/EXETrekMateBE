package com.trekmate.exe.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "device_public_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DevicePublicKey {

    @Id
    @Column(length = 16)
    private String userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant registeredAt = Instant.now();
}
