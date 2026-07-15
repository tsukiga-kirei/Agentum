package com.agentum.attachment.infrastructure;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRecognitionSettingRepository extends JpaRepository<AttachmentRecognitionSettingEntity, Short> {
}
