package com.vsp.notification.dispatcher.adapter.out;

import com.vsp.notification.dispatcher.domain.UserChannelPreference;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserChannelPreference, Long> {
    List<UserChannelPreference> findByUserId(String userId);
}
