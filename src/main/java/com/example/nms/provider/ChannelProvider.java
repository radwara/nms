package com.example.nms.provider;

import com.example.nms.common.Channel;

/**
 * A provider adapter: one vendor for one channel. {@link #send} is called outside any database
 * transaction and must return within {@code nms.worker.send-timeout} (default 10s; the claim lease
 * is longer), returning {@code TIMEOUT} itself if the vendor is slower. It returns the classified
 * result for vendor errors; anything it throws is treated as {@code TRANSIENT}.
 */
public interface ChannelProvider {

  /** Stored on the delivery and used in webhook paths, e.g. {@code sim-email}. */
  String name();

  Channel channel();

  SendResult send(SendRequest request);
}
