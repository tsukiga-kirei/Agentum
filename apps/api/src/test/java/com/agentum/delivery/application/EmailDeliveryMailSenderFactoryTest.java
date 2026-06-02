package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class EmailDeliveryMailSenderFactoryTest {

    @Test
    void shouldUsePlainTransportWhenTlsDisabledEvenOnPort465() {
        Properties properties = new Properties();
        EmailDeliverySmtpConfig smtp = new EmailDeliverySmtpConfig(
            "smtp.example.com",
            465,
            "user@example.com",
            "secret",
            "user@example.com",
            false
        );

        EmailDeliveryMailSenderFactory.applyTransportSecurity(properties, smtp);

        assertThat(smtp.transportMode()).isEqualTo(EmailDeliveryTransportMode.PLAIN);
        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
    }

    @Test
    void shouldEnableImplicitSslWhenTlsEnabledOnPort465() {
        Properties properties = new Properties();
        EmailDeliverySmtpConfig smtp = new EmailDeliverySmtpConfig(
            "smtp.example.com",
            465,
            "user@example.com",
            "secret",
            "user@example.com",
            true
        );

        EmailDeliveryMailSenderFactory.applyTransportSecurity(properties, smtp);

        assertThat(smtp.transportMode()).isEqualTo(EmailDeliveryTransportMode.SMTPS);
        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
    }

    @Test
    void shouldEnableStartTlsWhenRequestedOnPort587() {
        Properties properties = new Properties();
        EmailDeliverySmtpConfig smtp = new EmailDeliverySmtpConfig(
            "smtp.example.com",
            587,
            "user@example.com",
            "secret",
            "user@example.com",
            true
        );

        EmailDeliveryMailSenderFactory.applyTransportSecurity(properties, smtp);

        assertThat(smtp.transportMode()).isEqualTo(EmailDeliveryTransportMode.STARTTLS);
        assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(properties.getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
    }
}
