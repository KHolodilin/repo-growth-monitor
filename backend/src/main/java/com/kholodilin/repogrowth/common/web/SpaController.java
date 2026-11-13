package com.kholodilin.repogrowth.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the landing page and authorized Admin SPA routes. Public entity HTML is policy-resolved separately. */
@Controller
public class SpaController {

    private static final Resource INDEX = new ClassPathResource("static/index.html");

    @GetMapping(value = {
            "/",
            "/admin",
            "/admin/**"
    }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> spa(HttpServletRequest request) {
        if (!INDEX.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).contentType(MediaType.TEXT_HTML).body(INDEX);
    }
}
