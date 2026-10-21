package com.example.nms.directory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelPreferenceRepository
    extends JpaRepository<ChannelPreference, ChannelPreferenceId> {

  List<ChannelPreference> findByIdRecipientId(String recipientId);
}
