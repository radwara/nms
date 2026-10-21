package com.example.nms.provider;

import com.example.nms.common.Channel;

/**
 * A provider adapter: one vendor for one channel. {@link #send} returns the classified result and
 * does not throw for vendor errors; it is called outside any database transaction.
 */
public interface ChannelProvider {

  /** Stored on the delivery and used in webhook paths, e.g. {@code sim-email}. */
  String name();

  Channel channel();

  SendResult send(SendRequest request);
}
