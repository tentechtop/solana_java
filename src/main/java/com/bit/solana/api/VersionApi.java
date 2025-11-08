package com.bit.solana.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/version")
public class VersionApi {


    @GetMapping
    public String version() {
        return "2025.0.0.1";
    }

}
