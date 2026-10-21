package com.example.nms.directory;

import com.example.nms.common.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;

/** Composite key of {@link ChannelPreference}. */
@Embeddable
public record ChannelPreferenceId(
    @Column(name = "recipient_id") String recipientId, @Enumerated(EnumType.STRING) Channel channel)
    implements Serializable {}
