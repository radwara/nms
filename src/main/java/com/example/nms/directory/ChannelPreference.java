package com.example.nms.directory;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import org.hibernate.annotations.Immutable;

/** Per-channel opt-out. A missing row means the channel is enabled. Seeded, read-only. */
@Entity
@Immutable
public class ChannelPreference {

  @EmbeddedId private ChannelPreferenceId id;

  private boolean enabled;

  protected ChannelPreference() {}

  public ChannelPreferenceId getId() {
    return id;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
