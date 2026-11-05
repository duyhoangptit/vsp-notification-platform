package com.pnh.admin.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the bundled console (copied into {@code static/} by the Maven {@code prod} profile, T185): client-side routes
 * are forwarded to {@code index.html}; API, actuator and static asset paths are not matched.
 */
@Controller
class SpaForwardController {

    @GetMapping({
        "/",
        "/login",
        "/dashboard",
        "/templates",
        "/templates/**",
        "/channel-configs",
        "/notifications",
        "/notifications/**",
        "/config-change-log",
        "/alerts",
        "/admin/**"
    })
    String forward() {
        return "forward:/index.html";
    }
}
