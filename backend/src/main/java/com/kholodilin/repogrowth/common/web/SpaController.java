package com.kholodilin.repogrowth.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * A browser refresh of a client route reaches the server, so every section of the SPA is served the
 * same index.html and routed again on the client. Sections are matched by prefix because the router
 * grows nested pages, and the build only puts index.html and /assets under the static root, so no
 * real file can hide behind these paths.
 */
@Controller
public class SpaController {

    private static final Resource INDEX = new ClassPathResource("static/index.html");

    @GetMapping(value = {
            "/",
            "/dashboard",
            "/repositories/**",
            "/search-runs/**"
    }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> spa(HttpServletRequest request) {
        if (!INDEX.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(INDEX);
    }
}
