package com.kholodilin.collector.credentials;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
@FunctionalInterface
public interface CredentialResolver {CredentialLease resolve(String origin,CredentialRequest request);}
