package com.agentum.auth.infrastructure;

import com.agentum.auth.domain.UserExternalIdentityEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserExternalIdentityRepository extends JpaRepository<UserExternalIdentityEntity, UUID> {

    Optional<UserExternalIdentityEntity> findByProviderIdAndSubject(UUID providerId, String subject);
}
