package com.example.nms.directory;

import java.util.Collection;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipientRepository extends JpaRepository<Recipient, String> {

  /** The given ids that exist in the directory. Loads ids only, no contact details. */
  @Query("select r.id from Recipient r where r.id in :ids")
  Set<String> findExistingIds(@Param("ids") Collection<String> ids);
}
