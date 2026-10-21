package com.example.nms.directory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;

/** Directory entry; the only place contact details are stored. Seeded by Flyway, read-only. */
@Entity
@Immutable
public class Recipient {

  @Id private String id;

  private String email;

  private String phone;

  protected Recipient() {}

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPhone() {
    return phone;
  }
}
