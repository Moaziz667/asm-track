package com.asm.assistant.web;

import com.asm.assistant.answer.AnswerResponse;
import com.asm.assistant.answer.AnswerService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assistant's answer endpoint: grounded, cited answers with an explicit route/refusal/degraded
 * signal. Tenant-scoped and operator-gated by the shared security config.
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class QueryController {

    private final AnswerService answerService;

    public record QueryRequest(@NotBlank String query) {}

    @PostMapping("/query")
    public AnswerResponse query(@RequestBody QueryRequest req) {
        return answerService.answer(req.query());
    }
}
