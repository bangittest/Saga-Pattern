package com.demo.orchestrator;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class SagaController {

    private final SagaService saga;

    public SagaController(SagaService saga) {
        this.saga = saga;
    }

    /** Single entry point for the client. Returns the saga step-by-step log. */
    @PostMapping
    public Map<String, Object> place(@RequestBody OrderRequest req) {
        return saga.placeOrder(req);
    }
}