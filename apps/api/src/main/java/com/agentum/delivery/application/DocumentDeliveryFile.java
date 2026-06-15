package com.agentum.delivery.application;

public record DocumentDeliveryFile(
    String fileName,
    String contentType,
    byte[] bytes
) {
}
