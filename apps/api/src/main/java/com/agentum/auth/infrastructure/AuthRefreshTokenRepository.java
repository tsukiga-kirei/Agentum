package com.agentum.auth.infrastructure;

import com.agentum.auth.domain.AuthRefreshTokenEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshTokenEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthRefreshTokenEntity> findByTokenHash(String tokenHash);
}
