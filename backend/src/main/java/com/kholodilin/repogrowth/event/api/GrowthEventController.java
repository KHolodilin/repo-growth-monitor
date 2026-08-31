package com.kholodilin.repogrowth.event.api;

import com.kholodilin.repogrowth.event.application.GrowthEventService;
import com.kholodilin.repogrowth.event.domain.GrowthEvent;
import com.kholodilin.repogrowth.event.domain.GrowthEventSetting;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class GrowthEventController {

    private final GrowthEventService growthEventService;

    public GrowthEventController(GrowthEventService growthEventService) {
        this.growthEventService = growthEventService;
    }

    @GetMapping("/repositories/{id}/growth-events")
    public List<GrowthEvent> list(@PathVariable long id, @RequestParam(defaultValue = "30d") String period) {
        return growthEventService.list(id, period);
    }

    @PostMapping("/repositories/{id}/growth-events")
    public GrowthEvent create(@PathVariable long id, @RequestBody ManualEventRequest request) {
        return growthEventService.createManual(
                id,
                request.type(),
                request.eventAt(),
                request.title(),
                request.url(),
                request.description()
        );
    }

    @PutMapping("/growth-events/{id}")
    public GrowthEvent update(@PathVariable long id, @RequestBody ManualEventRequest request) {
        return growthEventService.updateManual(
                id,
                request.type(),
                request.eventAt(),
                request.title(),
                request.url(),
                request.description()
        );
    }

    @DeleteMapping("/growth-events/{id}")
    public void delete(@PathVariable long id) {
        growthEventService.deleteManual(id);
    }

    @GetMapping("/repositories/{id}/growth-event-settings")
    public List<GrowthEventSetting> settings(@PathVariable long id) {
        return growthEventService.settings(id);
    }

    @PutMapping("/repositories/{id}/growth-event-settings")
    public List<GrowthEventSetting> updateSettings(@PathVariable long id, @RequestBody List<GrowthEventSetting> settings) {
        return growthEventService.updateSettings(id, settings);
    }

    public record ManualEventRequest(
            String type,
            Instant eventAt,
            String title,
            String url,
            String description
    ) {
    }
}
