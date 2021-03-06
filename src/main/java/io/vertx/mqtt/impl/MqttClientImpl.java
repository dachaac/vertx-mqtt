/*
 * Copyright 2016 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vertx.mqtt.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.mqtt.MqttConnectPayload;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttConnectVariableHeader;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessageFactory;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubscribePayload;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubscribePayload;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.NetSocketInternal;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetClient;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.messages.MqttConnAckMessage;
import io.vertx.mqtt.MqttConnectionException;
import io.vertx.mqtt.messages.MqttSubAckMessage;
import io.vertx.mqtt.messages.MqttPublishMessage;

import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.netty.handler.codec.mqtt.MqttQoS.*;

/**
 * MQTT client implementation
 */
public class MqttClientImpl implements MqttClient {

  private static final Logger log = LoggerFactory.getLogger(MqttClientImpl.class);

  private static final int MAX_MESSAGE_ID = 65535;
  private static final int MAX_TOPIC_LEN = 65535;
  private static final int MIN_TOPIC_LEN = 1;
  private static final String PROTOCOL_NAME = "MQTT";
  private static final int PROTOCOL_VERSION = 4;

  private MqttClientOptions options;
  private MqttClientConnection connection;
  private final NetClient client;

  // handler to call when a publish is complete
  Handler<Integer> publishCompleteHandler;
  // handler to call when a unsubscribe request is completed
  Handler<Integer> unsubscribeCompleteHandler;
  // handler to call when a publish message comes in
  Handler<MqttPublishMessage> publishHandler;
  // handler to call when a subscribe request is completed
  Handler<MqttSubAckMessage> subscribeCompleteHandler;
  // handler to call when a connection request is completed
  Handler<AsyncResult<MqttConnAckMessage>> connectHandler;
  // handler to call when a pingresp is received
  Handler<Void> pingrespHandler;
  //handler to call when the remote MQTT server closes the connection
  Handler<Void> closeHandler;

  // counter for the message identifier
  private int messageIdCounter;

  // patterns for topics validation
  private Pattern validTopicNamePattern = Pattern.compile("^[^#+\\u0000]+$");
  private Pattern validTopicFilterPattern = Pattern.compile("^(#|((\\+(?![^/]))?([^#+]*(/\\+(?![^/]))?)*(/#)?))$");

  /**
   * Constructor
   *
   * @param vertx Vert.x instance
   * @param options MQTT client options
   */
  public MqttClientImpl(Vertx vertx, MqttClientOptions options) {
    this.client = vertx.createNetClient(options);
    this.options = options;
  }

  /**
   * See {@link MqttClient#connect()} for more details
   */
  @Override
  public MqttClient connect() {
    return connect(null);
  }

  /**
   * See {@link MqttClient#connect(Handler)} for more details
   */
  @Override
  public MqttClient connect(Handler<AsyncResult<MqttConnAckMessage>> connectHandler) {

    log.debug(String.format("Trying to connect with %s:%d", options.getHost(), options.getPort()));
    this.client.connect(options.getPort(), options.getHost(), options.getHost(), done -> {

      // the TCP connection fails
      if (done.failed()) {
        log.error(String.format("Can't connect to %s:%d", options.getHost(), options.getPort()), done.cause());
        if (connectHandler != null) {
          connectHandler.handle(Future.failedFuture(done.cause()));
        }
      } else {
        log.info(String.format("Connection with %s:%d established successfully", options.getHost(), options.getPort()));

        NetSocketInternal soi = (NetSocketInternal) done.result();
        ChannelPipeline pipeline = soi.channelHandlerContext().pipeline();
        this.connectHandler = connectHandler;

        if (options.isAutoGeneratedClientId() && (options.getClientId() == null || options.getClientId().isEmpty())) {
          options.setClientId(generateRandomClientId());
        }

        initChannel(pipeline);
        this.connection = new MqttClientConnection(this, soi, options);

        soi.messageHandler(msg -> connection.handleMessage(msg));
        soi.closeHandler(v -> handleClosed());

        MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.CONNECT,
          false,
          AT_MOST_ONCE,
          false,
          0);

        MqttConnectVariableHeader variableHeader = new MqttConnectVariableHeader(
          PROTOCOL_NAME,
          PROTOCOL_VERSION,
          options.hasUsername(),
          options.hasPassword(),
          options.isWillRetain(),
          options.getWillQoS(),
          options.isWillFlag(),
          options.isCleanSession(),
          options.getKeepAliveTimeSeconds()
        );

        MqttConnectPayload payload = new MqttConnectPayload(
          options.getClientId() == null ? "" : options.getClientId(),
          options.getWillTopic(),
          options.getWillMessage(),
          options.hasUsername() ? options.getUsername() : null,
          options.hasPassword() ? options.getPassword() : null
        );

        io.netty.handler.codec.mqtt.MqttMessage connect = MqttMessageFactory.newMessage(fixedHeader, variableHeader, payload);

        this.write(connect);
      }

    });
    return this;
  }

  /**
   * See {@link MqttClient#disconnect()} for more details
   */
  @Override
  public MqttClient disconnect() {
    return disconnect(null);
  }

  /**
   * See {@link MqttClient#disconnect(Handler)} for more details
   */
  @Override
  public MqttClient disconnect(Handler<AsyncResult<Void>> disconnectHandler) {

    MqttFixedHeader fixedHeader = new MqttFixedHeader(
      MqttMessageType.DISCONNECT,
      false,
      AT_MOST_ONCE,
      false,
      0
    );

    io.netty.handler.codec.mqtt.MqttMessage disconnect = MqttMessageFactory.newMessage(fixedHeader, null, null);

    this.write(disconnect);

    if (disconnectHandler != null) {
      disconnectHandler.handle(Future.succeededFuture());
    }

    this.connection.close();
    return this;
  }

  /**
   * See {@link MqttClient#publish(String, Buffer, MqttQoS, boolean, boolean)} for more details
   */
  @Override
  public MqttClient publish(String topic, Buffer payload, MqttQoS qosLevel, boolean isDup, boolean isRetain) {
    return publish(topic, payload, qosLevel, isDup, isRetain, null);
  }

  /**
   * See {@link MqttClient#publish(String, Buffer, MqttQoS, boolean, boolean, Handler)} for more details
   */
  @Override
  public MqttClient publish(String topic, Buffer payload, MqttQoS qosLevel, boolean isDup, boolean isRetain, Handler<AsyncResult<Integer>> publishSentHandler) {

    if (!isValidTopicName(topic)) {
      String msg = String.format("Invalid Topic Name - %s. It mustn't contains wildcards: # and +. Also it can't contains U+0000(NULL) chars", topic);
      log.error(msg);
      if (publishSentHandler != null) {
        publishSentHandler.handle(Future.failedFuture(msg));
      }
      return this;
    }

    MqttFixedHeader fixedHeader = new MqttFixedHeader(
      MqttMessageType.PUBLISH,
      isDup,
      qosLevel,
      isRetain,
      0
    );

    MqttPublishVariableHeader variableHeader = new MqttPublishVariableHeader(topic, nextMessageId());

    ByteBuf buf = Unpooled.copiedBuffer(payload.getBytes());

    io.netty.handler.codec.mqtt.MqttMessage publish = MqttMessageFactory.newMessage(fixedHeader, variableHeader, buf);

    this.write(publish);

    if (publishSentHandler != null) {
      publishSentHandler.handle(Future.succeededFuture(variableHeader.messageId()));
    }

    return this;
  }

  /**
   * See {@link MqttClient#publishCompleteHandler(Handler)} for more details
   */
  @Override
  public MqttClient publishCompleteHandler(Handler<Integer> publishCompleteHandler) {

    this.publishCompleteHandler = publishCompleteHandler;
    return this;
  }

  /**
   * See {@link MqttClient#publishHandler(Handler)} for more details
   */
  @Override
  public MqttClient publishHandler(Handler<MqttPublishMessage> publishHandler) {

    this.publishHandler = publishHandler;
    return this;
  }

  /**
   * See {@link MqttClient#subscribeCompleteHandler(Handler)} for more details
   */
  @Override
  public MqttClient subscribeCompleteHandler(Handler<MqttSubAckMessage> subscribeCompleteHandler) {

    this.subscribeCompleteHandler = subscribeCompleteHandler;
    return this;
  }

  /**
   * See {@link MqttClient#subscribe(String, int)} for more details
   */
  @Override
  public MqttClient subscribe(String topic, int qos) {
    return subscribe(topic, qos, null);
  }

  /**
   * See {@link MqttClient#subscribe(String, int, Handler)} for more details
   */
  @Override
  public MqttClient subscribe(String topic, int qos, Handler<AsyncResult<Integer>> subscribeSentHandler) {
    return subscribe(Collections.singletonMap(topic, qos), subscribeSentHandler);
  }

  /**
   * See {@link MqttClient#subscribe(Map)} for more details
   */
  @Override
  public MqttClient subscribe(Map<String, Integer> topics) {
    return subscribe(topics, null);
  }

  /**
   * See {@link MqttClient#subscribe(Map, Handler)} for more details
   */
  @Override
  public MqttClient subscribe(Map<String, Integer> topics, Handler<AsyncResult<Integer>> subscribeSentHandler) {

    Map<String, Integer> invalidTopics = topics.entrySet()
      .stream()
      .filter(e -> !isValidTopicFilter(e.getKey()))
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    if (invalidTopics.size() > 0) {
      String msg = String.format("Invalid Topic Filters: %s", invalidTopics);
      log.error(msg);
      if (subscribeSentHandler != null) {
        subscribeSentHandler.handle(Future.failedFuture(msg));
      }
      return this;
    }

    MqttFixedHeader fixedHeader = new MqttFixedHeader(
      MqttMessageType.SUBSCRIBE,
      false,
      AT_LEAST_ONCE,
      false,
      0);

    MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(nextMessageId());
    List<MqttTopicSubscription> subscriptions = topics.entrySet()
      .stream()
      .map(e -> new MqttTopicSubscription(e.getKey(), valueOf(e.getValue())))
      .collect(Collectors.toList());

    MqttSubscribePayload payload = new MqttSubscribePayload(subscriptions);

    io.netty.handler.codec.mqtt.MqttMessage subscribe = MqttMessageFactory.newMessage(fixedHeader, variableHeader, payload);

    this.write(subscribe);

    if (subscribeSentHandler != null) {
      subscribeSentHandler.handle(Future.succeededFuture(variableHeader.messageId()));
    }
    return this;
  }

  /**
   * See {@link MqttClient#unsubscribeCompleteHandler(Handler)} for more details
   */
  @Override
  public MqttClient unsubscribeCompleteHandler(Handler<Integer> unsubscribeCompleteHandler) {

    this.unsubscribeCompleteHandler = unsubscribeCompleteHandler;
    return this;
  }

  /**
   * See {@link MqttClient#unsubscribe(String, Handler)} )} for more details
   */
  @Override
  public MqttClient unsubscribe(String topic, Handler<AsyncResult<Integer>> unsubscribeSentHandler) {

    MqttFixedHeader fixedHeader = new MqttFixedHeader(
      MqttMessageType.UNSUBSCRIBE,
      false,
      AT_LEAST_ONCE,
      false,
      0);

    MqttMessageIdVariableHeader variableHeader = MqttMessageIdVariableHeader.from(nextMessageId());

    MqttUnsubscribePayload payload = new MqttUnsubscribePayload(Stream.of(topic).collect(Collectors.toList()));

    io.netty.handler.codec.mqtt.MqttMessage unsubscribe = MqttMessageFactory.newMessage(fixedHeader, variableHeader, payload);

    this.write(unsubscribe);

    if (unsubscribeSentHandler != null) {
      unsubscribeSentHandler.handle(Future.succeededFuture(variableHeader.messageId()));
    }
    return this;
  }

  /**
   * See {@link MqttClient#unsubscribe(String)} )} for more details
   */
  @Override
  public MqttClient unsubscribe(String topic) {
    return this.unsubscribe(topic, null);
  }

  /**
   * See {@link MqttClient#pingResponseHandler(Handler)} for more details
   */
  @Override
  public MqttClient pingResponseHandler(Handler<Void> pingResponseHandler) {

    this.pingrespHandler = pingResponseHandler;
    return this;
  }

  /**
   * See {@link MqttClient#closeHandler(Handler)} for more details
   */
  @Override
  public MqttClient closeHandler(Handler<Void> closeHandler) {
    this.closeHandler = closeHandler;
    return this;
  }

  /**
   * See {@link MqttClient#ping()} for more details
   */
  @Override
  public MqttClient ping() {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PINGREQ, false, MqttQoS.AT_MOST_ONCE, false, 0);

    io.netty.handler.codec.mqtt.MqttMessage pingreq = MqttMessageFactory.newMessage(fixedHeader, null, null);

    this.write(pingreq);

    return this;
  }

  @Override
  public String clientId() {
    return this.options.getClientId();
  }

  /**
   * Sends PUBACK packet to server
   *
   * @param publishMessageId identifier of the PUBLISH message to acknowledge
   */
  void publishAcknowledge(int publishMessageId) {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PUBACK, false, AT_MOST_ONCE, false, 0);

    MqttMessageIdVariableHeader variableHeader =
      MqttMessageIdVariableHeader.from(publishMessageId);

    io.netty.handler.codec.mqtt.MqttMessage puback = MqttMessageFactory.newMessage(fixedHeader, variableHeader, null);

    this.write(puback);
  }

  /**
   * Sends PUBREC packet to server
   *
   * @param publishMessageId identifier of the PUBLISH message to acknowledge
   */
  void publishReceived(int publishMessageId) {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PUBREC, false, AT_MOST_ONCE, false, 0);

    MqttMessageIdVariableHeader variableHeader =
      MqttMessageIdVariableHeader.from(publishMessageId);

    io.netty.handler.codec.mqtt.MqttMessage pubrec = MqttMessageFactory.newMessage(fixedHeader, variableHeader, null);

    this.write(pubrec);
  }

  /**
   * Sends PUBCOMP packet to server
   *
   * @param publishMessageId identifier of the PUBLISH message to acknowledge
   */
  void publishComplete(int publishMessageId) {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PUBCOMP, false, AT_MOST_ONCE, false, 0);

    MqttMessageIdVariableHeader variableHeader =
      MqttMessageIdVariableHeader.from(publishMessageId);

    io.netty.handler.codec.mqtt.MqttMessage pubcomp = MqttMessageFactory.newMessage(fixedHeader, variableHeader, null);

    this.write(pubcomp);
  }

  /**
   * Sends the PUBREL message to server
   *
   * @param publishMessageId  identifier of the PUBLISH message to acknowledge
   */
  void publishRelease(int publishMessageId) {

    MqttFixedHeader fixedHeader =
      new MqttFixedHeader(MqttMessageType.PUBREL, false, MqttQoS.AT_LEAST_ONCE, false, 0);

    MqttMessageIdVariableHeader variableHeader =
      MqttMessageIdVariableHeader.from(publishMessageId);

    io.netty.handler.codec.mqtt.MqttMessage pubrel = MqttMessageFactory.newMessage(fixedHeader, variableHeader, null);

    this.write(pubrel);
  }

  private void initChannel(ChannelPipeline pipeline) {

    // add into pipeline netty's (en/de)coder
    pipeline.addBefore("handler", "mqttEncoder", MqttEncoder.INSTANCE);
    pipeline.addBefore("handler", "mqttDecoder", new MqttDecoder());

    if (this.options.isAutoKeepAlive() &&
      this.options.getKeepAliveTimeSeconds() != 0) {

      pipeline.addBefore("handler", "idle",
        new IdleStateHandler(0, this.options.getKeepAliveTimeSeconds(), 0));
      pipeline.addBefore("handler", "keepAliveHandler", new ChannelDuplexHandler() {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

          if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
              ping();
            }
          }
        }
      });
    }
  }

  /**
   * Update and return the next message identifier
   *
   * @return message identifier
   */
  private int nextMessageId() {

    // if 0 or MAX_MESSAGE_ID, it becomes 1 (first valid messageId)
    this.messageIdCounter = ((this.messageIdCounter % MAX_MESSAGE_ID) != 0) ? this.messageIdCounter + 1 : 1;
    return this.messageIdCounter;
  }

  public MqttClientImpl write(io.netty.handler.codec.mqtt.MqttMessage mqttMessage) {

    synchronized (this.connection) {
      log.debug(String.format("Sending packet %s", mqttMessage));
      this.connection.writeMessage(mqttMessage);
      return this;
    }
  }

  /**
   * Used for calling the close handler when the remote MQTT server closes the connection
   */
  void handleClosed() {
    synchronized (this.connection) {
      if (this.closeHandler != null) {
        this.closeHandler.handle(null);
      }
    }
  }

  /**
   * Used for calling the pingresp handler when the server replies to the ping
   */
  void handlePingresp() {

    synchronized (this.connection) {
      if (this.pingrespHandler != null) {
        this.pingrespHandler.handle(null);
      }
    }
  }

  /**
   * Used for calling the unsuback handler when the server acks an unsubscribe
   *
   * @param unsubackMessageId identifier of the subscribe acknowledged by the server
   */
  void handleUnsuback(int unsubackMessageId) {

    synchronized (this.connection) {
      if (this.unsubscribeCompleteHandler != null) {
        this.unsubscribeCompleteHandler.handle(unsubackMessageId);
      }
    }
  }

  /**
   * Used for calling the puback handler when the server acknowledge a QoS 1 message with puback
   *
   * @param pubackMessageId identifier of the message acknowledged by the server
   */
  void handlePuback(int pubackMessageId) {

    synchronized (this.connection) {
      if (this.publishCompleteHandler != null) {
        this.publishCompleteHandler.handle(pubackMessageId);
      }
    }
  }

  /**
   * Used for calling the pubcomp handler when the server client acknowledge a QoS 2 message with pubcomp
   *
   * @param pubcompMessageId identifier of the message acknowledged by the server
   */
  void handlePubcomp(int pubcompMessageId) {

    synchronized (this.connection) {
      if (this.publishCompleteHandler != null) {
        this.publishCompleteHandler.handle(pubcompMessageId);
      }
    }
  }

  /**
   * Used for sending the pubrel when a pubrec is received from the server
   *
   * @param pubrecMessageId identifier of the message acknowledged by server
   */
  void handlePubrec(int pubrecMessageId) {

    synchronized (this.connection) {
      this.publishRelease(pubrecMessageId);
    }
  }

  /**
   * Used for calling the suback handler when the server acknoweldge subscribe to topics
   *
   * @param msg message with suback information
   */
  void handleSuback(MqttSubAckMessage msg) {

    synchronized (this.connection) {
      if (this.subscribeCompleteHandler != null) {
        this.subscribeCompleteHandler.handle(msg);
      }
    }
  }

  /**
   * Used for calling the publish handler when the server publishes a message
   *
   * @param msg published message
   */
  void handlePublish(MqttPublishMessage msg) {

    synchronized (this.connection) {

      switch (msg.qosLevel()) {

        case AT_MOST_ONCE:
          if (this.publishHandler != null) {
            this.publishHandler.handle(msg);
          }
          break;

        case AT_LEAST_ONCE:
          this.publishAcknowledge(msg.messageId());
          if (this.publishHandler != null) {
            this.publishHandler.handle(msg);
          }
          break;

        case EXACTLY_ONCE:
          this.publishReceived(msg.messageId());
          // TODO : save the PUBLISH message for raising the handler when PUBREL will arrive
          break;
      }

    }
  }

  /**
   * Used for calling the pubrel handler when the server acknowledge a QoS 2 message with pubrel
   *
   * @param pubrelMessageId identifier of the message acknowledged by the server
   */
  void handlePubrel(int pubrelMessageId) {

    synchronized (this.connection) {
      this.publishComplete(pubrelMessageId);

      // TODO : call publishHandler with the message received in the PUBISH
      //        we have to save it somewhere (maybe in a queue)
    }
  }

  /**
   * Used for calling the connect handler when the server replies to the request
   *
   * @param msg  connection response message
   */
  void handleConnack(MqttConnAckMessage msg) {

    synchronized (this.connection) {

      if (this.connectHandler != null) {

        if (msg.code() == MqttConnectReturnCode.CONNECTION_ACCEPTED) {
          this.connectHandler.handle(Future.succeededFuture(msg));
        } else {
          MqttConnectionException exception = new MqttConnectionException(msg.code());
          log.error(exception.getMessage());
          this.connectHandler.handle(Future.failedFuture(exception));
        }
      }
    }
  }

  /**
   * @return Randomly-generated ClientId
   */
  private String generateRandomClientId() {
    return UUID.randomUUID().toString();
  }

  /**
   * Check either given Topic Name valid of not
   *
   * @param topicName given Topic Name
   * @return true - valid, otherwise - false
   */
  private boolean isValidTopicName(String topicName) {
    if(!isValidStringSizeInUTF8(topicName)){
      return false;
    }

    Matcher matcher = validTopicNamePattern.matcher(topicName);
    return matcher.find();
  }

  /**
   * Check either given Topic Filter valid of not
   *
   * @param topicFilter given Topic Filter
   * @return true - valid, otherwise - false
   */
  private boolean isValidTopicFilter(String topicFilter) {
    if(!isValidStringSizeInUTF8(topicFilter)){
      return false;
    }

    Matcher matcher = validTopicFilterPattern.matcher(topicFilter);
    return matcher.find();
  }

  /**
   * Check either given string has size more then 65535 bytes in UTF-8 Encoding
   * @param string given string
   * @return true - size is lower or equal than 65535, otherwise - false
   */
  private boolean isValidStringSizeInUTF8(String string) {
    try {
      int length = string.getBytes("UTF-8").length;
      return length >= MIN_TOPIC_LEN && length <= MAX_TOPIC_LEN;
    } catch (UnsupportedEncodingException e) {
      log.error("UTF-8 charset is not supported", e);
    }

    return false;
  }
}
