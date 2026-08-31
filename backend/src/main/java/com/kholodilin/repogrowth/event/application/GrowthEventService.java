package com.kholodilin.repogrowth.event.application;

import com.kholodilin.repogrowth.analytics.application.DashboardPeriod;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.event.domain.GrowthEvent;
import com.kholodilin.repogrowth.event.domain.GrowthEventCatalog;
import com.kholodilin.repogrowth.event.domain.GrowthEventSetting;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventSettingJdbcRepository;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GrowthEventService {

    private final RepositoryService repositoryService;
    private final GrowthEventJdbcRepository eventRepository;
    private final GrowthEventSettingJdbcRepository settingRepository;
    private final PlanningWindow planningWindow;

    public GrowthEventService(
            RepositoryService repositoryService,
            GrowthEventJdbcRepository eventRepository,
            GrowthEventSettingJdbcRepository settingRepository,
            PlanningWindow planningWindow
    ) {
        this.repositoryService = repositoryService;
        this.eventRepository = eventRepository;
        this.settingRepository = settingRepository;
        this.planningWindow = planningWindow;
    }

    public List<GrowthEvent> list(long repositoryId, String periodParam) {
        repositoryService.get(repositoryId);
        DashboardPeriod period = DashboardPeriod.of(periodParam, planningWindow.businessDate(), LocalDate.of(2008, 1, 1));
        return eventRepository.findInPeriod(repositoryId, period.from(), period.to());
    }

    public GrowthEvent createManual(long repositoryId, String type, Instant eventAt, String title, String url, String description) {
        repositoryService.get(repositoryId);
        if (!GrowthEventCatalog.isManualType(type)) {
            throw ApiException.validation("Unknown manual growth event type");
        }
        if (eventAt == null) {
            throw ApiException.validation("Event date is required");
        }
        if (title == null || title.isBlank()) {
            throw ApiException.validation("Title is required");
        }
        return eventRepository.insertManual(
                repositoryId,
                eventAt,
                GrowthEventCatalog.category(type),
                type,
                title.trim(),
                blankToNull(description),
                blankToNull(url)
        );
    }

    public GrowthEvent updateManual(long id, String type, Instant eventAt, String title, String url, String description) {
        GrowthEvent existing = eventRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Growth event not found"));
        if (!existing.manual()) {
            throw ApiException.validation("Automatic growth events cannot be edited");
        }
        String nextType = type == null ? existing.type() : type;
        if (!GrowthEventCatalog.isManualType(nextType)) {
            throw ApiException.validation("Unknown manual growth event type");
        }
        Instant nextAt = eventAt == null ? existing.eventAt() : eventAt;
        String nextTitle = title == null || title.isBlank() ? existing.title() : title.trim();
        return eventRepository.updateManual(
                id,
                nextAt,
                nextType,
                GrowthEventCatalog.category(nextType),
                nextTitle,
                description == null ? existing.description() : blankToNull(description),
                url == null ? existing.url() : blankToNull(url)
        ).orElseThrow(() -> ApiException.notFound("Growth event not found"));
    }

    public void deleteManual(long id) {
        GrowthEvent existing = eventRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Growth event not found"));
        if (!existing.manual()) {
            throw ApiException.validation("Automatic growth events cannot be deleted");
        }
        eventRepository.deleteManual(id);
    }

    public List<GrowthEventSetting> settings(long repositoryId) {
        repositoryService.get(repositoryId);
        return settingRepository.ensureDefaults(repositoryId);
    }

    public List<GrowthEventSetting> updateSettings(long repositoryId, List<GrowthEventSetting> updates) {
        repositoryService.get(repositoryId);
        Map<String, Boolean> enabledByType = new LinkedHashMap<>();
        for (GrowthEventSetting update : updates) {
            if (!GrowthEventCatalog.isAutomaticType(update.eventType())) {
                throw ApiException.validation("Unknown automatic growth event type: " + update.eventType());
            }
            enabledByType.put(update.eventType(), update.enabled());
        }
        return settingRepository.replace(repositoryId, enabledByType);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
