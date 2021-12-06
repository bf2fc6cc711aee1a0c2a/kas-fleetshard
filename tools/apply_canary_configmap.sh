#!/usr/bin/env bash
#
# On NEW Kafka instance namespace create, creates a canary-config
# ConfigMap to enable verbose logging & reloads canary to pick up on
# the changes.
#
# Usage:
# oc observe namespaces --type-env-var='OBSERVE_EVENT_TYPE' -l 'bf2.org/managedkafka-id' -- ./apply_canary_configmap.sh
#

if [[ ${OBSERVE_EVENT_TYPE} == "Added" ]]; then
    echo 'apiVersion: v1
kind: ConfigMap
metadata:
  name: canary-config
  labels:
    created-by: oc-observe
data:
  sarama.log.enabled: "true"
  verbosity.log.level: "1"
  go.debug: "netdns=go+9"
' | oc apply -n ${1} -f -

    for canary in $(oc -n ${1} get deploy -l app.kubernetes.io/component=canary -o name --no-headers); do
        oc -n ${1} rollout restart ${canary}
    done

    while [[ $(oc get routes -l app.kubernetes.io/name=kafka -o=jsonpath='{.items[*].spec.host}' | wc -w) != 4 ]]; do
        echo "Waiting 5s for availability of Kafka routes before creating nslookup pod..."
        sleep 5
    done
    bootstrap_route=$(oc get routes -l app.kubernetes.io/name=kafka -o=jsonpath='{range .items[*]}{.spec.host}{"\n"}{end}' | grep -v broker)
    echo $bootstrap_route

    echo 'apiVersion: v1
kind: Pod
metadata:
  labels:
    created-by: oc-observe
  name: nslookup
spec:
  containers:
  - name: nslookup
    image: quay.io/grdryn/ubi8-bind-utils
    imagePullPolicy: Always
    args:
    - /usr/bin/bash
    - -c
    - |
      echo "Start: $(date)"
      echo "cat /etc/resolve.conf"
      cat /etc/resolv.conf

      # Run nslookup every 5 seconds for 30 mins
      for i in {0..360}; do
          date
          nslookup -debug ${KAFKA_BOOTSTRAP_DOMAIN}
          echo "========================="
          sleep 5
      done
      echo "End: $(date)"
    env:
    - name: KAFKA_BOOTSTRAP_DOMAIN
      value: #bootstrap_route
  restartPolicy: Never' | sed -e "s/#bootstrap_route/${bootstrap_route}/" | oc apply -n ${1} -f -
fi
