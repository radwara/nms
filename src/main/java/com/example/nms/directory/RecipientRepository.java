package com.example.nms.directory;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipientRepository extends JpaRepository<Recipient, String> {

  /** The given ids that exist in the directory. Loads ids only, no contact details. */
  @Query("select r.id from Recipient r where r.id in :ids")
  Set<String> findExistingIds(@Param("ids") Collection<String> ids);

  /** Address presence of the given recipients, in one query. Loads no contact details. */
  @Query(
      """
      select new com.example.nms.directory.AddressPresence(
          r.id,
          case when r.email is not null then true else false end,
          case when r.phone is not null then true else false end)
      from Recipient r where r.id in :ids
      """)
  List<AddressPresence> findAddressPresence(@Param("ids") Collection<String> ids);
}
