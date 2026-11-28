package com.vsp.notification.alerting.adapter.out;

import com.vsp.notification.alerting.domain.AlertRecipient;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRecipientRepository extends JpaRepository<AlertRecipient, Long> {
    List<AlertRecipient> findAllByOrderByEmailAsc();

    List<AlertRecipient> findByActiveTrueOrderByEmailAsc();

    Optional<AlertRecipient> findByEmail(String email);
}
