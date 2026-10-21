package com.example.nms.directory;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChannelPreferenceRepository
    extends JpaRepository<ChannelPreference, ChannelPreferenceId> {

  List<ChannelPreference> findByIdRecipientId(String recipientId);

  /** The opted-out (recipient, channel) pairs of the given recipients, in one query. */
  @Query(
      "select p.id from ChannelPreference p where p.id.recipientId in :ids and p.enabled = false")
  List<ChannelPreferenceId> findOptOuts(@Param("ids") Collection<String> ids);
}
