(ns bartnet.launch
  (:require [bartnet.s3-buckets :as buckets]
            [bartnet.nsq :as nsq]
            [cheshire.core :refer :all]
            [instaparse.core :as insta]
            [clostache.parser :refer [render-resource]]
            [clojure.java.io :as io]
            [amazonica.aws.cloudformation :refer [create-stack]]
            [amazonica.aws.ec2 :refer [describe-images]]
            [amazonica.aws.sns :as sns]
            [amazonica.aws.sqs :as sqs]
            [clojure.tools.logging :as log]
            [clj-time.format :as f]
            [clj-yaml.core :as yaml]
            [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [opsee.middleware.core :refer :all]
            [clojure.core.match :refer [match]]
            [bartnet.instance :as instance])
  (:import [java.util.concurrent ExecutorService TimeUnit ScheduledFuture]
           [java.util Base64]))

(def formatter (f/formatters :date-time))

(def auth-addr (atom nil))

(def beta-map (reduce #(assoc %1 %2 (buckets/url-to %2 "beta/bastion-cf.template")) {} buckets/regions))

(def beat-delay 10)

(def slack-url "https://hooks.slack.com/services/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")

(def support-ssh-key "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQDP+VmyztGmJJTe6YtMtrKazGy3tQC/Pku156Ae10TMzCjvtiol+eL11FKyvNvlENM5EWwIQEng5w3J616kRa92mWr9OWALBn4HJZcztS2YLAXyiC+GLauil6W6xnGzS0DmU5RiYSSPSrmQEwHvmO2umbG190srdaDn/ZvAwptC1br/zc/7ya3XqxHugw1V9kw+KXzTWSC95nPkhOFoaA3nLcMvYWfoTbsU/G08qQy8medqyK80LJJntedpFAYPUrVdGY2J7F2y994YLfapPGzDjM7nR0sRWAZbFgm/BSD0YM8KA0mfGZuKPwKSLMtTUlsmv3l6GJl5a7TkyOlK3zzYtVGO6dnHdZ3X19nldreE3DywpjDrKIfYF2L42FKnpTGFgvunsg9vPdYOiJyIfk6lYsGE6h451OAmV0dxeXhtbqpw4/DsSHtLm5kKjhjRwunuQXEg8SfR3kesJjq6rmhCjLc7bIKm3rSU07zbXSR40JHO1Mc9rqzg2bCk3inJmCKWbMnDvWU1RD475eATEKoG/hv0/7EOywDnFe1m4yi6yZh7XlvakYsxDBPO9/FMlZm2T+cn+TyTmDiw9tEAIEAEiiu18CUNIii1em7XtFDmXjGFWfvteQG/2A98/uDGbmlXd64F2OtU/ulDRJXFGaji8tqxQ/To+2zIeIptLjtqBw==")

(def cf-parser
  (insta/parser
   "message = line+
     line = key <'='> value <'\\n'>
     key = #'[a-zA-Z]+'
     value = <'\\''> (#'(?s).')* <'\\''>"))

(defn encode-user-data [data]
  (let [encoder (Base64/getMimeEncoder)]
    (.encodeToString encoder (.getBytes data))))

(defn get-latest-stable-image [creds owner-id tag]
  ; We dissoc access-key and secret-key here, because they are the customer's credentials and, for whatever
  ; reason, customers can't find our AMIs like this even when they're public. Removing them from the "creds" hash
  ; will cause the AWS client to get the credentials it needs from the environment or instance profile. -greg
  (let [{images :images} (describe-images (dissoc creds :access-key :secret-key) :owners [owner-id] :filters [{:name "tag:release" :values [tag]}
                                                                                                              {:name "is-public" :values [true]}])]
    (first (sort-by :name #(compare %2 %1) images))))

(defn get-bastion-creds [customer-id]
  (let [response (http/post @auth-addr {:content-type :json :accept :json :body (generate-string {:customer_id customer-id})})]
    (case (:status response)
      200 (parse-string (:body response) keyword)
      (throw (Exception. "failed to get bastion credentials from vape")))))

(defn attributes->state [attributes]
  (case [(get attributes :ResourceStatus) (get attributes :ResourceType)]
    ["CREATE_COMPLETE" "AWS::CloudFormation::Stack"] "complete"
    ["ROLLBACK_COMPLETE" "AWS::CloudFormation::Stack"] "failed"
    "launching"))

(defn generate-env-shell [env]
  (str/join "\n" (map
                  (fn [[k v]]
                    (str (name k) "=" v))
                  env)))

(defn generate-user-data [customer-id customer-email bastion-creds bastion-config] ;ca cert key]
  (str
   "#cloud-config\n"
   (yaml/generate-string
    {:users [{:name "opsee"
              :groups ["sudo" "docker"]
              :ssh-authorized-keys [support-ssh-key]}]
     :write_files [{:path "/etc/opsee/bastion-env.sh"
                    :permissions "0644"
                    :owner "root"
                    :content (generate-env-shell
                              {:CUSTOMER_ID customer-id
                               :CUSTOMER_EMAIL customer-email
                               ; It's possible at some point that we want to make this configurable.
                               ; At that point we'll want to associate a customer with a specific
                               ; bastion version and expose a mechanism to change that version.
                               ; Until then, always launch with the stable version.
                               :BASTION_VERSION "stable"
                               :BASTION_ID (:id bastion-creds)
                               :VPN_PASSWORD (:password bastion-creds)
                               :VPN_REMOTE (:vpn-remote bastion-config)
                               :DNS_SERVER (:dns-server bastion-config)
                               :BARTNET_HOST (:bartnet-host bastion-config)
                               :BASTION_AUTH_TYPE (:bastion-auth-type bastion-config)
                               :NSQD_HOST (:nsqd-host bastion-config)})}]
     :coreos {:update
              {:reboot-strategy "off"
               :group "beta"}}})))

(defn parse-cloudformation-msg [instance-id msg]
  (if-let [msg-str (:Message msg)]
    (try
      (let [parsed (cf-parser msg-str)
            attributes (reduce (fn [obj token]
                                 (match token
                                   :message obj
                                   [:line [:key key] [:value & strings]] (assoc obj
                                                                                (keyword key)
                                                                                (str/join "" strings))))
                               {} parsed)
            state (attributes->state attributes)]
        {:attributes attributes
         :instance_id instance-id
         :state state})
      (catch Exception e (pprint msg) (log/error e "exception on parse")))))

(defn launch-heartbeat [producer customer-id instance-id]
  (fn []
    (nsq/publish! producer {:command "launch-bastion"
                            :customer_id customer-id
                            :instance-id instance-id
                            :state "launching"})))

(defn send-slack-error-msg [customer-id email user-id region err]
  (let [tmpl {:text "*BASTION LAUNCH FAILURE*"
              :username "ErrorBot"
              :icon_url "https://s3-us-west-1.amazonaws.com/opsee-public-images/slack-avi-48-red.png"
              :attachments [{:text (str "Customer: " customer-id)
                             :color "#f44336"
                             :fields [{:title "Email" :value email :short true}
                                      {:title "User ID" :value user-id :short true}
                                      {:title "Region" :value region :short true}
                                      {:title "Last Error" :value err}]}]}]
    (http/post slack-url {:form-params {:payload (generate-string tmpl)}})))

(defn launcher [creds bastion-creds bastion-config producer image-id instance-type vpc-id subnet-id login keypair template-src executor]
      (fn []
          (try-let
            [id (:id bastion-creds)
             customer-id (:customer_id login)
             customer-email (:email login)
             state (atom nil)
             cloudfailure (atom nil)
             endpoint (keyword (:endpoint creds))
             template-map (if-let [res (:resource template-src)]
                                  {:template-body (-> res
                                                      io/resource
                                                      slurp)}
                                  {:template-url (buckets/url-to endpoint (:template-url template-src))})
             {topic-arn :topic-arn} (sns/create-topic creds {:name (str "opsee-bastion-build-sns-" id)})
             {queue-url :queue-url} (sqs/create-queue creds {:queue-name (str "opsee-bastion-build-sqs-" id)})
             {queue-arn :QueueArn} (sqs/get-queue-attributes creds {:queue-url queue-url :attribute-names ["All"]})
             launch-beater (.scheduleAtFixedRate executor (launch-heartbeat producer customer-id id) beat-delay beat-delay TimeUnit/SECONDS)
             policy (render-resource "templates/sqs_policy.mustache" {:policy-id id :queue-arn queue-arn :topic-arn topic-arn})
             _ (sqs/set-queue-attributes creds queue-url {"Policy" policy})
             {subscription-arn :SubscriptionArn} (sns/subscribe creds topic-arn "sqs" queue-arn)]
            (log/info queue-url endpoint template-map)
            (log/info "subscribe" topic-arn "sqs" queue-arn)
            (log/info "launching stack with " image-id vpc-id customer-id)
            (create-stack creds
                          (merge {:stack-name (str "opsee-bastion-" id)
                                  :capabilities ["CAPABILITY_IAM"]
                                  :parameters [{:parameter-key "ImageId" :parameter-value image-id}
                                               {:parameter-key "InstanceType" :parameter-value instance-type}
                                               {:parameter-key "UserData" :parameter-value (encode-user-data
                                                                                             (generate-user-data customer-id customer-email bastion-creds bastion-config))}
                                               {:parameter-key "VpcId" :parameter-value vpc-id}
                                               {:parameter-key "SubnetId" :parameter-value subnet-id}
                                               {:parameter-key "KeyName" :parameter-value keypair}]
                                  :tags [{:key "Name" :value (str "Opsee Bastion " customer-id)}]
                                  :notification-arns [topic-arn]} template-map))
            (loop [{messages :messages} (sqs/receive-message creds {:queue-url queue-url})]
                  (if
                    (not-any? #(true? %)
                              (for [message messages
                                    :let [msg-body (:body message)
                                          msg (parse-cloudformation-msg id (parse-string msg-body true))]]
                                   (do
                                     (sqs/delete-message creds (assoc message :queue-url queue-url))
                                     (nsq/publish! producer (assoc msg :command "launch-bastion"
                                                                       :customer_id customer-id))
                                     (log/info (get-in msg [:attributes :ResourceType]) (get-in msg [:attributes :ResourceStatus]))
                                     (if (= (get-in msg [:attributes :ResourceStatus]) "CREATE_FAILED") (reset! cloudfailure (get-in msg [:attributes :ResourceStatusReason])))
                                     (reset! state (:state msg))
                                     (contains? #{"complete" "failed"} (:state msg)))))
                    (recur (sqs/receive-message creds {:queue-url queue-url
                                                       :wait-time-seconds 20}))))
            (log/info "exiting" id)
            (nsq/publish! producer {:command "launch-bastion"
                                    :customer_id customer-id
                                    :instance_id id
                                    :state "ok"
                                    :attributes {:status :success}})
            (if (= "failed" @state)
              (send-slack-error-msg customer-id (:email login) (:id login) (:endpoint creds) @cloudfailure)
              (instance/discover! {:access_key (:access-key creds)
                                   :secret_key (:secret-key creds)
                                   :region (:endpoint creds)
                                   :customer_id customer-id
                                   :user_id (:id login)}))
            (catch Exception ex (do
                                  (log/error ex "Exception in thread")
                                  (send-slack-error-msg customer-id (:email login) (:id login) (:endpoint creds) (.getMessage ex))))
            (finally (do
                       (log/info "cleaning up")
                       (.cancel launch-beater false)
                       (sns/delete-topic creds topic-arn)
                       (sqs/delete-queue creds queue-url))))))

(defn launch-bastions [^ExecutorService executor scheduler producer login msg ami-config bastion-config]
  (let [access-key (:access-key msg)
        secret-key (:secret-key msg)
        regions (:regions msg)
        instance-size (:instance-size msg)
        customer-id (:customer_id login)
        customer-email (:email login)
        owner-id (:owner-id ami-config)
        tag (:tag ami-config)
        keypair (if (:admin login) (:keypair ami-config) "")
        template-src (:template-src ami-config)]
    (log/info regions)
    (for [region-obj regions]
      (let [creds {:access-key access-key
                   :secret-key secret-key
                   :endpoint (:region region-obj)}
            {image-id :image-id} (get-latest-stable-image creds owner-id tag)
            vpcs (for [vpc (:vpcs region-obj)]
                   (do (log/info vpc)
                       (let [bastion-creds (get-bastion-creds customer-id)
                             vpc-id (:id vpc)
                             subnet-id (:subnet_id vpc)]
                         (.submit
                          executor
                          (launcher creds bastion-creds bastion-config producer
                                    image-id instance-size
                                    vpc-id subnet-id login keypair
                                    template-src scheduler))
                         (assoc vpc :instance_id (:id bastion-creds)))))]
        (assoc region-obj :vpcs vpcs)))))

