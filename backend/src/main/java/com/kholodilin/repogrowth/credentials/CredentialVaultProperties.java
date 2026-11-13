package com.kholodilin.repogrowth.credentials;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("rgm.credentials")
public record CredentialVaultProperties(boolean enabled,String activeKeyId,Map<String,String> encryptionKeys,Duration cacheLease) {
    public CredentialVaultProperties {
        encryptionKeys=encryptionKeys==null?Map.of():Map.copyOf(encryptionKeys);
        cacheLease=cacheLease==null?Duration.ofHours(2):cacheLease;
        if(cacheLease.isZero() || cacheLease.isNegative() || cacheLease.compareTo(Duration.ofHours(24))>0) throw new IllegalArgumentException("Credential cache lease must be positive and at most 24h");
        if(enabled && (activeKeyId==null || !encryptionKeys.containsKey(activeKeyId))) throw new IllegalArgumentException("An external active encryption key is required");
    }
    @Override public String toString() {return "CredentialVaultProperties[encryptionKeys=REDACTED]";}
}
