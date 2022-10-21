# th2-conn-soup-bin-tcp

This microservice allows sending and receiving messages via SoupBinTCP protocol

## Configuration

+ *sessions* - list of session settings
+ *maxBatchSize* - max size of outgoing message batch (`1000` by default)
+ *maxFlushTime* - max message batch flush time (`1000` by default)
+ *batchByGroup* - batch messages by group instead of session alias and direction (`true` by default)
+ *publishSentEvents* - enables/disables publish of "message sent" events (`true` by default)
+ *publishConnectEvents* - enables/disables publish of "connect/disconnect" events (`true` by default)

## Session settings

+ *sessionAlias* - session alias for incoming/outgoing th2 messages
+ *sessionGroup* - session group for incoming/outgoing th2 messages (same as session alias by default)
+ *handler* - handler settings
+ *mangler* - mangler settings

## Handler settings

+ *host* - service host
+ *port* - service port
+ *security* - connection security settings
+ *username* - login username
+ *password* - login password
+ *requestedSession* - requested login session (empty by default)
+ *requestedSequenceNumber* - requested login sequence number (`0` by default)
+ *heartbeatInterval* - millisecond interval for sending client heartbeats (`1000` by default)
+ *idleTimeout* - millisecond interval to disconnect after if there were no incoming messages (`15000` by default)
+ *autoReconnect* - enables/disables auto-reconnect (`true` by default)
+ *reconnectDelay* - delay between reconnects (`5000` by default)
+ *maxMessageRate* - max outgoing message rate for this session (unlimited by default)

### Security settings

+ *ssl* - enables SSL on connection (`false` by default)
+ *sni* - enables SNI support (`false` by default)
+ *certFile* - path to server certificate (`null` by default)
+ *acceptAllCerts* - accept all server certificates (`false` by default, takes precedence over `certFile`)

**NOTE**: when using infra 1.7.0+ it is recommended to load value for `certFile` from a secret by using `${secret_path:secret_name}` syntax.

## Mangler settings

There's no mangler implementation at the moment

## MQ pins

+ input queue with `subscribe`, `send` and `raw` attributes for outgoing messages
+ output queue with `publish`, `first` (for incoming messages) or `second` (for outgoing messages) and `raw` attributes

## Deployment via infra-mgr

Here's an example of `infra-mgr` config required to deploy this service

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: soup-bin-tcp-conn
spec:
  image-name: ghcr.io/th2-net/th2-conn-soup-bin-tcp
  image-version: 0.0.1
  type: th2-conn
  custom-config:
    maxBatchSize: 1000
    maxFlushTime: 1000
    batchByGroup: false
    publishSentEvents: true
    publishConnectEvents: true
    sessions:
      - sessionAlias: sbt-session
        handler:
          security:
            ssl: false
          host: 127.0.0.1
          port: 4567
          autoReconnect: true
          reconnectDelay: 5000
          maxMessageRate: 100000
        mangler: { }
  pins:
    - name: to_send
      connection-type: mq
      attributes:
        - subscribe
        - send
        - raw
      settings:
        storageOnDemand: false
        queueLength: 1000
    - name: outgoing
      connection-type: mq
      attributes:
        - publish
        - store
        - raw
  extended-settings:
    service:
      enabled: false
    resources:
      limits:
        memory: 500Mi
        cpu: 1000m
      requests:
        memory: 100Mi
        cpu: 100m
```
