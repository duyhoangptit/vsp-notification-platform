package com.pnh.tracking.application;

import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.web.PnhException;
import com.pnh.tracking.NotificationView;
import com.pnh.tracking.adapter.out.NotificationJdbcRepository;
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
