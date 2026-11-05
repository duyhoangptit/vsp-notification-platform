package com.pnh.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CryptoConfig {

    @Bean
    EnvelopeEncryptor envelopeEncryptor(
            @Value("${pnh.crypto.master-key:}") String masterKey,
            @Value("${pnh.crypto.master-key-id:k1}") String keyId) {
        return new EnvelopeEncryptor(masterKey, keyId);
    }
}
