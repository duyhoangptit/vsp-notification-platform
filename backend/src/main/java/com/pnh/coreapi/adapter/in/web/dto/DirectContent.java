package com.pnh.coreapi.adapter.in.web.dto;

import jakarta.validation.constraints.Size;
import java.util.Map;

public record DirectContent(
        @Size(max = 255) String subject, @Size(max = 255) String title, String body, Map<String, String> data) {}
