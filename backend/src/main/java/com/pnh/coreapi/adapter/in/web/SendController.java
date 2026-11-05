package com.pnh.coreapi.adapter.in.web;

import com.pnh.coreapi.ClientPrincipal;
import com.pnh.coreapi.adapter.in.web.dto.SendAccepted;
import com.pnh.coreapi.adapter.in.web.dto.SendRequest;
import com.pnh.coreapi.application.SubmitNotificationUseCase;
import com.pnh.coreapi.application.SubmitResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/notifications/send} (scope SEND): 202 new, 200 + Idempotent-Replay for duplicates. */
@RestController
class SendController {

    private final SubmitNotificationUseCase useCase;

    SendController(SubmitNotificationUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v1/notifications/send")
    ResponseEntity<SendAccepted> send(
            @Valid @RequestBody SendRequest request, @AuthenticationPrincipal ClientPrincipal client) {
        SubmitResult result = useCase.submit(request, client);
        var n = result.notification();
        SendAccepted body = new SendAccepted(
                n.notificationId(), n.clientRequestId(), n.status().name(), n.createdAt());
        return result.replay()
                ? ResponseEntity.ok().header("Idempotent-Replay", "true").body(body)
                : ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}
