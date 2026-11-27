package com.kholodilin.repogrowth.credentials.configuration;

import com.kholodilin.repogrowth.collection.configuration.GitHubBudgetProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CredentialVaultProperties.class, GitHubBudgetProperties.class})
public class CredentialConfiguration {}
