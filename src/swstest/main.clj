(ns swstest.main
  (:use swstest.tasks)
  (:import [com.amazonaws.auth AWSCredentials PropertiesCredentials]
           [com.amazonaws.services.simpleworkflow AmazonSimpleWorkflowClient]
           [com.amazonaws AmazonServiceException ClientConfiguration Protocol]
           [com.amazonaws.services.simpleworkflow.model PollForActivityTaskRequest PollForDecisionTaskRequest TaskList RespondDecisionTaskCompletedRequest Decision DecisionType ScheduleActivityTaskDecisionAttributes ActivityType CompleteWorkflowExecutionDecisionAttributes])
  (:gen-class))

(defn get-client [region]
  (let [creds (PropertiesCredentials. (.getResourceAsStream (clojure.lang.RT/baseLoader) "aws.properties"))
        config (ClientConfiguration.)]
    (. config (setProtocol Protocol/HTTPS))
    (. config (setMaxErrorRetry 3))
    (. config (setSocketTimeout 70000))
    (. config (setConnectionTimeout 70000))
   ;; (. config (setProxyHost "sltarray02"))
   ;; (. config (setProxyPort 8080))
    (doto (AmazonSimpleWorkflowClient. creds config) (.setEndpoint region))))

(def client (get-client "swf.us-east-1.amazonaws.com"))

(defn poll-for-activity [domain identity tasklist]
  (let [req (doto (PollForActivityTaskRequest.) (.withDomain domain) (.withIdentity identity) (.withTaskList (doto (TaskList.) (.withName tasklist))))]
    (. client (pollForActivityTask req))))

(defn poll-for-decision [domain identity tasklist]
  (let [req (doto (PollForDecisionTaskRequest.) (.withDomain domain) (.withIdentity identity) (.withTaskList (doto (TaskList.) (.withName tasklist))) (.withReverseOrder (Boolean. "true")))]
    (. client (pollForDecisionTask req))))

(defn worker [id tasklist]
  (while true
    (let [task (poll-for-activity "Messaging" id tasklist)
          _ (prn "TASK " task)
          type (.getActivityType task)]      
      (cond
       (= "sendmail" (.getName type)) (sendmail client task)
       (= "sendsms" (.getName type)) (sendsms client task)))))

(defn schedule-task [input token activity-type activity-id tasklist]
  (let [dec (doto (Decision.) (.withDecisionType DecisionType/ScheduleActivityTask)
                  (.withScheduleActivityTaskDecisionAttributes
                   (doto (ScheduleActivityTaskDecisionAttributes.)
                     (.withActivityType (doto (ActivityType.) (.withName activity-type) (.withVersion "1.0")))
                     (.withActivityId activity-id)
                     (.withInput input)
                     (.withScheduleToCloseTimeout "30")
                     (.withHeartbeatTimeout "60")
                     (.withTaskList (doto (TaskList.) (.withName tasklist))))))
        _ (prn "DEC " dec)
        req (doto (RespondDecisionTaskCompletedRequest.) (.withTaskToken token) (.withDecisions (list dec)))] 
    (. client (respondDecisionTaskCompleted req))))

(defn complete-workflow [token]
  (let [dec (doto (Decision.) (.withDecisionType DecisionType/CompleteWorkflowExecution)
                  (.withCompleteWorkflowExecutionDecisionAttributes
                   (doto (CompleteWorkflowExecutionDecisionAttributes.)
                     (.withResult "OK"))))
        req (doto (RespondDecisionTaskCompletedRequest.) (.withTaskToken token) (.withDecisions (list dec)))] 
    (. client (respondDecisionTaskCompleted req))))

(defn- get-input [event]
  (let [attr (.getWorkflowExecutionStartedEventAttributes event)]
    (. attr getInput)))

(defn decider [id tasklist]
  (while true
    (let [decision (poll-for-decision "Messaging" id tasklist)
          type (.getWorkflowType decision)
          task-token (.getTaskToken decision)
          events (vec (.getEvents decision))
          last-event (first events)]
      (prn "EVENTS " events)
      (cond
       (= (.getEventType (events 2)) "ActivityTaskCompleted") (complete-workflow task-token)
       (= (.getEventType (events 2)) "ActivityTaskFailed") (schedule-task (get-input (last events)) task-token "sendsms" "test-2" tasklist)
       (= (.getEventType last-event) "DecisionTaskStarted") (schedule-task (get-input (last events)) task-token "sendmail" "test-1" tasklist)
       (= (.getEventType last-event) "WorkflowExecutionTimedOut") (prn "WF Timeout")
       (= (.getEventType last-event) "DecisionTaskTimedOut") (prn "Task Timeout")
       (= (.getEventType last-event) "ScheduleActivityTaskFailed") (prn "Sched task failed")))))

(defn -main [type id tasklist]
  (cond
   (= type "W") (worker id tasklist)
   (= type "D") (decider id tasklist)))