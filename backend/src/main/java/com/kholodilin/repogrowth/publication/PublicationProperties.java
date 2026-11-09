package com.kholodilin.repogrowth.publication;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("rgm.publication")
public record PublicationProperties(Duration verificationMaxAge){public PublicationProperties{verificationMaxAge=verificationMaxAge==null?Duration.ofHours(24):verificationMaxAge;if(verificationMaxAge.isNegative()||verificationMaxAge.isZero())throw new IllegalArgumentException("Positive verification age required");}}
