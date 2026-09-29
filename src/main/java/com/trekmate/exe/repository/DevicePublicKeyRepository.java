package com.trekmate.exe.repository;

import com.trekmate.exe.model.DevicePublicKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DevicePublicKeyRepository extends JpaRepository<DevicePublicKey, String> {
}
