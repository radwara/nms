package com.example.nms.directory;

import com.example.nms.common.Channel;

/**
 * Which contact addresses a recipient has, without the addresses themselves: routing needs only
 * whether a channel is reachable, so contact details stay in the directory.
 */
public record AddressPresence(String recipientId, boolean hasEmail, boolean hasPhone) {

  public boolean hasAddressFor(Channel channel) {
    return switch (channel) {
      case EMAIL -> hasEmail;
      case SMS -> hasPhone;
    };
  }
}
