package com.vsp.notification.provider.adapter.out;

import com.vsp.notification.provider.domain.ChannelConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, Long> {

    Optional<ChannelConfig> findFirstByChannelTypeAndActiveTrue(String channelType);

    List<ChannelConfig> findAllByOrderByChannelTypeAscProviderKeyAsc();

    Optional<ChannelConfig> findByChannelTypeAndProviderKey(String channelType, String providerKey);

    /** Connection-test bookkeeping only: a bulk JPQL update does not bump the optimistic {@code version}. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChannelConfig c SET c.lastTestAt = :at, c.lastTestResult = :result WHERE c.id = :id")
    int recordTest(@Param("id") long id, @Param("at") Instant at, @Param("result") String result);
}
