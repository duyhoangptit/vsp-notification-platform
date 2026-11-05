package com.pnh.dispatcher.application;

import com.pnh.dispatcher.adapter.out.UserPreferenceRepository;
import com.pnh.dispatcher.domain.UserChannelPreference;
import com.pnh.provider.ChannelRegistry;
import com.pnh.shared.audit.AuditContext;
import com.pnh.shared.audit.ConfigChanged;
import com.pnh.shared.domain.Category;
import com.pnh.shared.domain.ChannelType;
import com.pnh.shared.domain.ErrorCode;
import com.pnh.shared.infra.CacheInvalidationBus;
import com.pnh.shared.infra.CacheNames;
import com.pnh.shared.web.PnhException;
import com.pnh.template.TemplateApi;
import com.pnh.template.TemplateInfo;
import com.pnh.tracking.NotificationView;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User channel preferences (T128) + the opt-out rule (T129, FR-008). Cached per user in {@code userPreferences} (60 s),
 * evicted cluster-wide on change; changes are audited with actor {@code client_id:user_id}.
 */
@Service("preferenceService")
public class PreferenceService implements PreferenceRule {

    private final UserPreferenceRepository repository;
    private final ChannelRegistry registry;
    private final TemplateApi templates;
    private final ApplicationEventPublisher events;
    private final CacheInvalidationBus bus;
    private final Cache cache;
    private final Clock clock;

    public PreferenceService(
            UserPreferenceRepository repository,
            ChannelRegistry registry,
            TemplateApi templates,
            ApplicationEventPublisher events,
            CacheInvalidationBus bus,
            CacheManager cacheManager,
            Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.templates = templates;
        this.events = events;
        this.bus = bus;
        this.cache = cacheManager.getCache(CacheNames.USER_PREFERENCES);
        this.clock = clock;
    }

    /** Explicit switches of the user (channel → enabled); absent channel ⇒ enabled. */
    @SuppressWarnings("unchecked")
    public Map<String, Boolean> explicit(String userId) {
        return cache.get(
                userId,
                () -> repository.findByUserId(userId).stream()
                        .collect(Collectors.toMap(
                                UserChannelPreference::getChannelType, UserChannelPreference::isEnabled)));
    }

    /** Effective preferences for every registered channel. */
    public Map<String, Boolean> effective(String userId) {
        Map<String, Boolean> explicit = explicit(userId);
        Map<String, Boolean> out = new TreeMap<>();
        for (ChannelType c : registry.registeredChannels()) {
            out.put(c.value(), explicit.getOrDefault(c.value(), true));
        }
        return out;
    }

    public boolean isEnabled(String userId, String channel) {
        return userId == null || explicit(userId).getOrDefault(channel, true);
    }

    @Transactional
    public Map<String, Boolean> update(String clientId, String userId, Map<String, Boolean> channels) {
        for (String key : channels.keySet()) {
            ChannelType ch = ChannelType.parseOrNull(key);
            if (ch == null || !registry.isRegistered(ch)) {
                throw PnhException.field(
                        ErrorCode.UNSUPPORTED_CHANNEL, "channels." + key, "Channel " + key + " is not supported");
            }
        }
        String actor = clientId + ":" + userId;
        AuditContext.runAs(actor, () -> {
            Map<String, UserChannelPreference> existing = repository.findByUserId(userId).stream()
                    .collect(Collectors.toMap(UserChannelPreference::getChannelType, p -> p));
            channels.forEach((rawKey, enabled) -> {
                String channel = ChannelType.of(rawKey).value();
                UserChannelPreference p = existing.get(channel);
                boolean before = p == null || p.isEnabled();
                String action = p == null ? "CREATE" : "UPDATE";
                if (p == null) {
                    p = new UserChannelPreference(userId, channel, enabled);
                } else if (p.isEnabled() == enabled) {
                    return;
                } else {
                    p.setEnabled(enabled);
                }
                UserChannelPreference saved = repository.saveAndFlush(p);
                events.publishEvent(new ConfigChanged(
                        "USER_PREFERENCE",
                        userId + ":" + channel,
                        action,
                        saved.getVersion(),
                        Map.of("is_enabled", before),
                        Map.of("is_enabled", enabled),
                        Set.of(),
                        Set.of(),
                        actor,
                        clock.instant()));
            });
            return null;
        });
        bus.evict(CacheNames.USER_PREFERENCES, userId);
        return effective(userId);
    }

    @Override
    public Decision evaluate(NotificationView n) {
        if (isEnabled(n.userId(), n.channelType())) {
            return Decision.ALLOW;
        }
        if (n.category() == Category.OTP) {
            return Decision.OVERRIDE;
        }
        boolean critical = n.templateCode() != null
                && templates.find(n.templateCode()).map(TemplateInfo::critical).orElse(false);
        return critical ? Decision.OVERRIDE : Decision.BLOCK;
    }
}
