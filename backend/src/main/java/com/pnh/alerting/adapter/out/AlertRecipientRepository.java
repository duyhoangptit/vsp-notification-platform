package com.pnh.alerting.adapter.out;

import com.pnh.alerting.domain.AlertRecipient;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRecipientRepository extends JpaRepository<AlertRecipient, Long> {
    List<AlertRecipient> findAllByOrderByEmailAsc();

    List<AlertRecipient> findByActiveTrueOrderByEmailAsc();

    Optional<AlertRecipient> findByEmail(String email);
}
