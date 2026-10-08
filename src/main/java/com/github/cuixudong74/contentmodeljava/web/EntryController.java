package com.github.cuixudong74.contentmodeljava.web;

import com.github.cuixudong74.contentmodeljava.service.EntryService;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryView;
import com.github.cuixudong74.contentmodeljava.web.dto.PublishRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * Content entry read/write endpoints. Stage: DRAFT (default) or PUBLISHED.
 */
@RestController
@RequestMapping("/api/content-types/{typeId}/entries")
public class EntryController {

    private final EntryService entryService;

    public EntryController(EntryService entryService) {
        this.entryService = entryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntryView create(@PathVariable String typeId, @RequestBody EntryRequest request) {
        return entryService.create(typeId, request);
    }

    @GetMapping
    public List<EntryView> list(@PathVariable String typeId,
                                @RequestParam(defaultValue = "DRAFT") String stage,
                                @RequestParam(required = false) String locale,
                                @RequestParam(required = false) String fallback) {
        return entryService.list(typeId, stage, locale, parseFallback(fallback));
    }

    @GetMapping("/{entryId}")
    public EntryView get(@PathVariable String typeId, @PathVariable String entryId,
                         @RequestParam(defaultValue = "DRAFT") String stage,
                         @RequestParam(required = false) String locale,
                         @RequestParam(required = false) String fallback) {
        return entryService.get(typeId, entryId, stage, locale, parseFallback(fallback));
    }

    @PutMapping("/{entryId}/draft")
    public EntryView updateDraft(@PathVariable String typeId, @PathVariable String entryId,
                                 @RequestBody EntryRequest request) {
        return entryService.updateDraft(typeId, entryId, request);
    }

    @PostMapping("/{entryId}/publish")
    public EntryView publish(@PathVariable String typeId, @PathVariable String entryId,
                             @RequestBody(required = false) PublishRequest request) {
        return entryService.publish(typeId, entryId, request);
    }

    @DeleteMapping("/{entryId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpublish(@PathVariable String typeId, @PathVariable String entryId) {
        entryService.unpublish(typeId, entryId);
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String typeId, @PathVariable String entryId) {
        entryService.delete(typeId, entryId);
    }

    private List<String> parseFallback(String fallback) {
        if (fallback == null || fallback.isBlank()) {
            return List.of();
        }
        return Arrays.stream(fallback.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
