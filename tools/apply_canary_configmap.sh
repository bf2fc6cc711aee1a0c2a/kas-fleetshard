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
' | oc apply -n ${1} -f -

    for canary in $(oc -n ${1} get deploy -l app.kubernetes.io/component=canary -o name --no-headers); do
        oc -n ${1} rollout restart ${canary}
    done
fi
