package com.kholodilin.collector;

import org.springframework.context.annotation.Configuration;

/** Execution module boundary. Storage is separately enabled; HTTP acceptance and task loops follow later. */
@Configuration(proxyBeanMethods = false)
public class CollectorModuleConfiguration { }
