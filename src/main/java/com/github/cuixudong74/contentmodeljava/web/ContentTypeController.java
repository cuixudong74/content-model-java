package com.github.cuixudong74.contentmodeljava.web;

import com.github.cuixudong74.contentmodeljava.service.ContentTypeService;
import com.github.cuixudong74.contentmodeljava.web.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.ContentTypeView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Content type definition and evolution endpoints.
 */
@RestController
@RequestMapping("/api/content-types")
public class ContentTypeController {

    private final ContentTypeService contentTypeService;

    public ContentTypeController(ContentTypeService contentTypeService) {
        this.contentTypeService = contentTypeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContentTypeView create(@RequestBody ContentTypeRequest request) {
        return contentTypeService.create(request);
    }

    @GetMapping("/{id}")
    public ContentTypeView get(@PathVariable String id) {
        return contentTypeService.get(id);
    }

    @GetMapping
    public List<ContentTypeView> list() {
        return contentTypeService.list();
    }

    @PutMapping("/{id}/evolve")
    public ContentTypeView evolve(@PathVariable String id, @RequestBody ContentTypeRequest request) {
        return contentTypeService.evolve(id, request);
    }
}
