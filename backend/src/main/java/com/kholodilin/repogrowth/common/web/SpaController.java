package com.kholodilin.repogrowth.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    private static final Resource INDEX = new ClassPathResource("static/index.html");

    @GetMapping(value = {
            "/",
            "/repositories",
            "/repositories/{id:[0-9]+}",
            "/search-runs/{id:[0-9]+}"
    }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> spa(HttpServletRequest request) {
        if (!INDEX.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(INDEX);
    }
}
