#!/usr/bin/env bash

echo "INFO: Attempting infra node pod rebalancing..."

AZ_COUNT=$(oc describe nodes | grep -e "failure-domain.beta.kubernetes.io/zone" | uniq | wc -l)

echo "INFO: Number of AZs ${AZ_COUNT}"

rebalancePods() {
    APP=$1
    NS=$2
    VOLUME_NAME=$3
    ALL_POD_NODES=$(oc get pods -n $NS -l app=$APP -o wide --no-headers | awk '{print $7}' | sort | wc -l)
    UNIQUE_POD_NODES=$(oc get pods -n $NS -l app=$APP -o wide --no-headers | awk '{print $7}' | sort | uniq | wc -l)
    echo "INFO: $APP pods needing rebalance: $(( ALL_POD_NODES - UNIQUE_POD_NODES ))"
    LAST_POD_NODE_NAME=""
    for POD in $( oc get pods -n $NS -l app=$APP -o jsonpath='{.items[*].metadata.name}' ) ; do
        POD_NODE_NAME=$(oc get pod -n $NS $POD -o json | jq -r .spec.nodeName)
        if [ "${POD_NODE_NAME}" == "${LAST_POD_NODE_NAME}" ]; then
            if [ "${AZ_COUNT}" != "1" ]; then
                PVC=$(oc get pod -n $NS $POD -o json | jq -r '.spec.volumes[] | select(.name=="'$VOLUME_NAME'") | .persistentVolumeClaim.claimName')
                echo "INFO: Deleting PVC $PVC"
                oc delete pvc --wait=false -n $NS $PVC
            fi
            echo "INFO: Deleting pod $POD"
            oc delete pod -n $NS $POD
        fi
        LAST_POD_NODE_NAME=$POD_NODE_NAME
    done
}

checkPendingPods() {
    APP=$1
    NS=$2
    for POD in $( oc get pods -n $NS -l app=$APP -o jsonpath='{.items[*].metadata.name}' ) ; do
        POD_STATUS_PHASE=$(oc get pods -n $NS $POD -o json | jq -r .status.phase)
        if [ "${POD_STATUS_PHASE}" == "Pending" ]; then
            echo "INFO: Deleting pod $POD"
            oc delete pod -n $NS $POD
        fi
    done
}

waitRunningPods() {
    APP=$1
    NS=$2
    for POD in $( oc get pods -n $NS -l app=$APP -o jsonpath='{.items[*].metadata.name}' ) ; do
        echo "INFO: Waiting for $POD to be Running..."
        while [ "$(oc get pod -n $NS $POD -o jsonpath='{.status.phase}' 2>/dev/null)" != "Running" ];
        do
            sleep 1
        done
    done
}

# elevate privileges so we can do this: https://github.com/openshift/ops-sop/blob/master/v4/howto/elevate-privileges.md
oc adm groups add-users osd-sre-cluster-admins $(oc whoami) >/dev/null
while [ "$(oc adm policy who-can patch resourcequota logging-storage-quota -n openshift-logging 2>&1 | grep Error |wc -l | xargs echo)" != "0" ];
do
    sleep 2
done

echo "INFO: Rebalancing prometheus pods..."
rebalancePods prometheus openshift-monitoring prometheus-data

echo "INFO: Rebalancing alertmanager pods..."
rebalancePods alertmanager openshift-monitoring alertmanager-data

echo "INFO: Restarting prometheus operator..."
OPERATOR_POD=$( oc get pod -n openshift-monitoring -l app.kubernetes.io/name=prometheus-operator -o jsonpath='{.items[*].metadata.name}' )
oc delete pod -n openshift-monitoring $OPERATOR_POD

echo "INFO: Check pending prometheus pods..."
checkPendingPods prometheus openshift-monitoring

echo "INFO: Check pending alertmanager pods..."
checkPendingPods alertmanager openshift-monitoring

echo "INFO: Wait for running prometheus pods..."
waitRunningPods prometheus openshift-monitoring

echo "INFO: Wait for running alertmanager pods..."
waitRunningPods alertmanager openshift-monitoring
