package com.wassim.reckon.controller;

import com.wassim.reckon.dto.QaRequest;
import com.wassim.reckon.dto.QaResponse;
import com.wassim.reckon.service.QaService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping
    public QaResponse ask(@Valid @RequestBody QaRequest request) {
        return qaService.answer(request.question());
    }
}
