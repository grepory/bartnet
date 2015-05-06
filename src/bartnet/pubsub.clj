(ns bartnet.pubsub
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [clojure.tools.logging :as log]
            [manifold.bus :as b]
            [amazonica.aws.sns :as sns])
  (:import [org.cliffc.high_scale_lib NonBlockingHashMap]))


(defn- bastion-topic [id]
  (str id "-bastion"))

(defn- command-topic [id]
  (str id "-commands"))

(def ^{:private true} sns-bastion-topic-arn (atom nil))

(defn- sns-bastion-topic []
  (let [arn @sns-bastion-topic-arn]
    (if-not arn (reset! sns-bastion-topic-arn (sns/create-topic "bastions")))
    arn))

(defprotocol Subscription
  (add-stream [this stream])
  (close [this]))

(defprotocol SendableConn
  (send-and-recv [this cmd msg]))

(defprotocol PubSub
  (register-bastion [this connection msg])
  (register-ws-client [this connection])
  (subscribe-bastion [this customer-id])
  (subscribe-command [this customer-id])
  (publish-command [this customer-id msg])
  (publish-bastion [this customer-id msg])
  (get-bastions [this customer-id])
  (send-msg [this id cmd msg]))

(defrecord PubSubBus [bus bastions ws-clients]
  PubSub
  (register-bastion [_ connection msg]
    (let [id (:customer-id connection)
          stream (b/subscribe bus (command-topic id))]
      (log/info connection)
      (.put bastions (:id connection) {:connection connection, :stream stream, :registration msg})
      (sns/publish {:topic-arn (sns-bastion-topic) :subject id :message msg})
      (b/publish! bus (bastion-topic id) msg)
      stream))
  (register-ws-client [_ connection]
    (let [id (:customer-id connection)
          stream (b/subscribe bus (bastion-topic id))]
      (.put ws-clients (:id connection) {:connection connection, :stream stream})
      stream))
  (subscribe-bastion [_ customer-id]
    (b/subscribe bus (command-topic customer-id)))
  (subscribe-command [_ customer-id]
    (b/subscribe bus (command-topic customer-id)))
  (publish-command [_ customer-id msg]
    (b/publish! bus (command-topic customer-id) msg))
  (publish-bastion [_ customer-id msg]
    (b/publish! bus (bastion-topic customer-id) msg))
  (get-bastions [this customer-id]
    (for [brec (.values bastions)
          :when (= customer-id (:customer-id (:connection brec)))]
      brec))
  (send-msg [_ id cmd msg]
    (log/info "bastions " bastions)
    (if-let [connrec (.get bastions id)]
      (send-and-recv (:connection connrec) cmd msg))))

(defn create-pubsub []
  (PubSubBus. (b/event-bus) (NonBlockingHashMap.) (NonBlockingHashMap.)))