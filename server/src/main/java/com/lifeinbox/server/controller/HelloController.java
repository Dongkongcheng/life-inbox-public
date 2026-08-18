package com.lifeinbox.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inbox")
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello LifeInbox";
    }
}