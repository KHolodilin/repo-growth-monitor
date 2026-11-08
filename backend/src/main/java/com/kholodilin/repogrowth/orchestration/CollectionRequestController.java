package com.kholodilin.repogrowth.orchestration;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** POST is protected by the existing Admin authorization manager and CSRF filter. */
@RestController
@ConditionalOnProperty(name="rgm.orchestration.enabled",havingValue="true")
@RequestMapping("/api/v1/admin/repositories")
public class CollectionRequestController {
    private final IdempotentCollectionRequests service;
    private final PlanningWindow window;
    public CollectionRequestController(IdempotentCollectionRequests service,PlanningWindow window) {this.service=service;this.window=window;}
    @PostMapping("/{id}/collections")
    public ResponseEntity<CollectionRequestService.Created> create(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(((com.kholodilin.repogrowth.auth.security.AppUserPrincipal)authentication.getPrincipal()).userId(),key,id,window.businessDate()));
    }
}
