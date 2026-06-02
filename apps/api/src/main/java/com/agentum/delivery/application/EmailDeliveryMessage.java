package com.agentum.delivery.application;

import java.nio.file.Path;
import java.util.List;

public record EmailDeliveryMessage(
    List<String> to,
    List<String> cc,
    List<String> bcc,
    String subject,
    String body,
    List<Path> attachmentPaths
) {
}
