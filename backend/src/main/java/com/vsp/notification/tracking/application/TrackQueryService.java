package com.vsp.notification.tracking.application;

import com.vsp.notification.shared.domain.ErrorCode;
import com.vsp.notification.shared.web.PnhException;
import com.vsp.notification.tracking.NotificationView;
import com.vsp.notification.tracking.adapter.out.NotificationJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Client tracking by client_request_id, scoped to the calling client (T104). */
@Service
public class TrackQueryService {

    private final NotificationJdbcRepository repository;

    public TrackQueryService(NotificationJdbcRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public NotificationView track(String clientId, String clientRequestId) {
        return repository
                .findByClientRequest(clientId, clientRequestId)
                .orElseThrow(
                        () -> new PnhException(ErrorCode.NOT_FOUND, "No notification with this client_request_id"));
    }
}
