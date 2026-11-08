package com.kholodilin.bootstrap;

import java.io.IOException;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

/** Excludes the entire legacy product scan in the collector-only role. */
public final class ProductRoleFilter implements TypeFilter, EnvironmentAware {
    private Environment environment;
    @Override public void setEnvironment(Environment environment) { this.environment = environment; }
    @Override public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
        return !environment.getProperty("rgm.web.enabled", Boolean.class, true);
    }
}
