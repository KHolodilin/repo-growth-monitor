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
    private final CollectionRequestService service;
    private final PlanningWindow window;
    public CollectionRequestController(CollectionRequestService service,PlanningWindow window) {this.service=service;this.window=window;}
    @PostMapping("/{id}/collections")
    public ResponseEntity<CollectionRequestService.Created> create(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.manual(id,window.businessDate()));
    }
}
