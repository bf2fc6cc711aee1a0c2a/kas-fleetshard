#!/usr/bin/env bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
MULTI_AZ="true"
REPO_ROOT="${DIR}/../"
SED=sed
GREP=grep
DATE=date
if [[ "$OSTYPE" == "darwin"* ]]; then
    SED=gsed
    GREP=ggrep
    DATE=gdate
fi

#########################################################
# Parse args
#########################################################

function usage() {
    echo "
    Requirement is jq installed
    NOTE! If you use --cluster-conf-file arguments like name,region,aws* etc... are ignored
    cloud-token-file  is required otherwise script cant connect to the cloud

    Option
        --set-storageclass                          change storageclass to unencrypted esb
        --install-addon ADDON_ID                    install selected addon id
        --remove-addon ADDON_ID                     uninstall selected addon id
        --set-ingress-controller                    setup ingresscontroller
        --infra-pod-rebalance                       infra pod rebalace (workaround for OHSS-2174)
        --get credentials|api_url|kubeconfig|kube_admin_login  get data from cluster
        --scale-count NUM                           scales the worker count
        --extend-expiration NUM                     extends the cluster expiration by NUM days
        --o|output  FILE                            output for kubeconfig
        --create                                    create cluster
        --delete                                    delete cluster
        --cloud-token-file FILE                     cloud redhat ocm token in file
        --token TOKEN                               cloud redhat ocm token
        -f|--cluster-conf-file FILE                 configuration file in json format
        --aws-sec-credentials-file FILE             aws security credentials csv
        --aws-account-id ID                         id of aws account
        -n|--name CLUSER_NAME                       name fo cluster
        -r|--region REGION                          region in aws (i.e. us-west-1)
        --flavor FLAVOR                             aws flavor (i.e. m5.xlarge)
        --multi-az                                  aws multi availablility (true | false) (default ${MULTI_AZ})
        --count COUNT                               number of nodes (i.e. 4)
        --wait                                      wait for cluster installation complete
        --version                                   version of OSD cluster (default latest released)
        --aws-access-key AWS_ACCESS_KEY             aws credentials access key
        --aws-secret-access-key AWS_SECRET_ACCESS_KEY  aws credentials secret access key

        [USAGE]
        Get info:
            ./osd-provision.sh --get credentials
            ./osd-provision.sh --get api_url
            ./osd-provision.sh --get kubeconfig --name test-cluster --output kafka-config

        Install cluster:
            ./osd-provision.sh --create --cloud-token-file ~/cloud-token.txt --aws-sec-credentials-file ~/aws-admin.csv --aws-account-id 4545454545454 --name test-cluster --region us-east-1 --flavor m5.xlarge --count 4 --wait

            ./osd-provision.sh --create --cloud-token-file ~/cloud-token.txt --aws-sec-credentials-file ~/aws-admin.csv --cluster-conf-file ~/cluster-config.json --aws-account-id 4545454545454 --wait

        Delete cluster
            ./osd-provision.sh --delete --cloud-token-file ~/cloud-token.txt --name test-cluster

            ./osd-provision.sh --delete --cloud-token-file ~/cloud-token.txt --f ~/cluster-config.json

        Set storageclass
            ./scripts/osd-provision.sh --set-storageclass --name cluster-name

        Infra pod rebalance
            ./scripts/osd-provision.sh --infra-pod-rebalance --name cluster-name

        Install addon
            ./scripts/osd-provision.sh --install-addon managed-kafka --name cluster-name

        Remove addon
            ./scripts/osd-provision.sh --remove-addon managed-kafka --name cluster-name
        
        Extend expiration date (for example 3 more days)
            ./scripts/osd-provision.sh --extend-expiration 3 --name cluster-name
        
        Hibernate cluster
            ./scripts/osd-provision.sh --hibernate --name cluster-name
        
        Resume cluster
            ./scripts/osd-provision.sh --resume --name cluster-name
    "
}

while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
    --get)
        OPERATION="get"
        DATA_FOR_GET="$2"
        shift # past argument
        shift # past value
        ;;
    -o | --output)
        OUTPUT="$2"
        shift # past argument
        shift # past value
        ;;
    --create)
        OPERATION="create"
        shift # past argument
        ;;
    --hibernate)
        OPERATION="hibernate"
        shift # past argument
        ;;
    --resume)
        OPERATION="resume"
        shift # past argument
        ;;
    --install-addon)
        OPERATION="install-addon"
        ADDON_ID="$2"
        shift # past argument
        shift # past value
        ;;
    --remove-addon)
        OPERATION="remove-addon"
        ADDON_ID="$2"
        shift # past argument
        shift # past value
        ;;
    --set-storageclass)
        OPERATION="set-storageclass"
        shift # past argument
        ;;
    --set-ingress-controller)
        OPERATION="set-ingress-controller"
        shift # past argument
        ;;
    --infra-pod-rebalance)
        OPERATION="infra-pod-rebalance"
        shift # past argument
        ;;
    --scale-count)
        OPERATION="scale-count"
        NODE_COUNT="$2"
        shift # past argument
        shift # past value
        ;;
    --extend-expiration)
        OPERATION="extend-expiration"
        EXTEND_DAYS="$2"
        shift # past argument
        shift # past value
        ;;
    --delete)
        OPERATION="delete"
        shift # past argument
        ;;
    --cloud-token-file)
        TOKEN_FILE="$2"
        shift # past argument
        shift # past value
        ;;
    --token)
        TOKEN="$2"
        shift # past argument
        shift # past value
        ;;
    -f | --cluster-conf-file)
        CLUSTER_JSON="$2"
        shift # past argument
        shift # past value
        ;;
    --aws-sec-credentials-file)
        AWS_CSV_PATH="$2"
        shift # past argument
        shift # past value
        ;;
    --aws-account-id)
        AWS_ACCOUNT_ID="$2"
        shift # past argument
        shift # past value
        ;;
    -n | --name)
        CLUSTER_NAME="$2"
        shift # past argument
        shift # past value
        ;;
    --version)
        VERSION="$2"
        shift # past argument
        shift # past value
        ;;
    -r | --region)
        REGION="$2"
        shift # past argument
        shift # past value
        ;;
    --flavor)
        FLAVOR="$2"
        shift # past argument
        shift # past value
        ;;
    --multi-az)
        MULTI_AZ="$2"
        shift # past argument
        shift # past value
        ;;
    --count)
        NODE_COUNT="$2"
        shift # past argument
        shift # past value
        ;;
    --aws-access-key)
        AWS_ACCESS_KEY="$2"
        shift # past argument
        shift # past value
        ;;
    --aws-secret-access-key)
        AWS_SECRET_ACCESS_KEY="$2"
        shift # past argument
        shift # past value
        ;;
    --wait)
        WAIT="true"
        shift # past argument
        ;;
    -h | --help) # unknown option
        usage
        exit
        ;;
    *) # unknown option
        shift
        ;;
    esac
done

#########################################################
# functions
#########################################################

function print_vars() {
    echo "OPERATION: ${OPERATION}"
    echo "OUTPUT: ${OUTPUT}"
    echo "CLUSTER_JSON: ${CLUSTER_JSON}"
    if [[ -z "${TOKEN}" ]]; then 
        TOKEN=$(cat ${TOKEN_FILE})
    fi
    echo "TOKEN: ${TOKEN}"
    echo "AWS_CSV_PATH: ${AWS_CSV_PATH}"
    echo "AWS_ACCOUNT_ID: ${AWS_ACCOUNT_ID}"
    echo "CLUSTER_NAME: ${CLUSTER_NAME}"
    echo "VERSION ${VERSION}"
    echo "REGION: ${REGION}"
    echo "FLAVOR: ${FLAVOR}"
    echo "MULTI_AZ: ${MULTI_AZ}"
    echo "AWS_ACCESS_KEY: ${AWS_ACCESS_KEY}"
    echo "AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}"
    echo "ADDON_ID: ${ADDON_ID}"
}

function set_default() {
    if [[ "${VERSION}" == "" ]]; then
        VERSION=$($OCM list versions --default)
    fi
}

function download_ocm() {
    url="https://github.com/openshift-online/ocm-cli/releases/download/v0.1.60"
    if [[ "$OSTYPE" == "linux"* ]]; then
        url="${url}/ocm-linux-amd64"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        url="${url}/ocm-darwin-amd64"
    fi
    echo "Downloading ocm tool: ${url} output: ${OCM}"
    curl -Ls "${url}" --output "${OCM}"
    chmod +x "${OCM}"
}

function get_cluster_name_from_config() {
    if [[ "$CLUSTER_JSON" == "" ]]; then
        CLUSTER_JSON="${REPO_ROOT}/cluster_config.json"
    fi
    name=$(cat "$CLUSTER_JSON" | jq .name | $SED -e 's/\"//g')
    echo "${name}"
}

function wait_for_cluster_install() {
    READY_COUNTER=0
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    echo "Waiting for cluster creation"
    READY="false"
    while [ $READY == "false" ]; do
        current=$($OCM list clusters | $GREP "\s${CLUSTER_NAME}\s" | awk '{print $8}')
        ver=$($OCM list clusters | $GREP "\s${CLUSTER_NAME}\s" | awk '{print $4}')
        echo "Status of cluster ${CLUSTER_NAME} is ${current} and version ${ver}"
        if [[ $current == "ERROR" ]] || [[ $current == "error" ]]; then
            echo "Cluster state is error, stopping script"
            echo "Getting ocm cluster logs"
            mkdir -p ${RESULT_DIR}
            touch "${RESULT_DIR}/${CLUSTER_NAME}"_install_logs.json
            $OCM get "/api/clusters_mgmt/v1/clusters/$(get_cluster_id $CLUSTER_NAME)/logs/install" | tee "${RESULT_DIR}/${CLUSTER_NAME}"_install_logs.json
            exit 1
        fi
        if [[ $current == "ready" ]] && [[ $ver == "NONE" ]]; then
            READY_COUNTER=$((READY_COUNTER+1))
        fi
        if [[ $current == "ready" ]] && ([[ $ver != "NONE" ]] || [ $READY_COUNTER -eq 10 ]); then
            READY="true"
            cred=$(get_credentials)
            echo "To connect to cluster use this command:"
            echo "oc login -u $(echo $cred | jq .user | sed -e s/\"//g) -p $(echo $cred | jq .password | sed -e s/\"//g) $(get_api_url)"
        else
            sleep 120
        fi
    done
}

function read_aws_csv() {
    if [[ "${AWS_CSV_PATH}" != "" ]]; then
        cred=$($SED '2q;d' "${AWS_CSV_PATH}")
        AWS_ACCESS_KEY="$(cut -d',' -f1 <<<"${cred//[$'\t\r\n']/}")"
        AWS_SECRET_ACCESS_KEY="$(cut -d',' -f2 <<<"${cred//[$'\t\r\n']/}")"
    else
        echo "AWS Credentials file is not provided -> going to get cred from arguments"
    fi
}

function build_config_json() {
    set_default
    config_file="${REPO_ROOT}/cluster_config.json"
    template_file="${DIR}/../templates/CCS_DEFINITION.template"
    rm -rf "${config_file}"
    cp -n "${template_file}" "${config_file}"
    $SED -i -e "s@##CLUSTER_NAME##@${CLUSTER_NAME}@g" "${config_file}"
    $SED -i -e "s@##REGION##@${REGION}@g" "${config_file}"
    $SED -i -e "s@##COMPUTE_NODES##@${NODE_COUNT}@g" "${config_file}"
    $SED -i -e "s@##AWS_ACCOUNT_ID##@${AWS_ACCOUNT_ID}@g" "${config_file}"
    $SED -i -e "s@##MACHINE_FLAVOR##@${FLAVOR}@g" "${config_file}"
    $SED -i -e "s@##AWS_ACCESS_KEY##@${AWS_ACCESS_KEY}@g" "${config_file}"
    $SED -i -e "s@##VERSION##@${VERSION}@g" "${config_file}"
    $SED -i -e "s@##AWS_SECRET_ACCESS_KEY##@${AWS_SECRET_ACCESS_KEY}@g" "${config_file}"
    $SED -i -e "s@##MULTI_AZ##@${MULTI_AZ}@g" "${config_file}"
    cat "${config_file}"
    CLUSTER_JSON="${config_file}"
}

function build_addon_template() {
    name="${1}"
    config_file="${REPO_ROOT}/addon-${name}.json"
    template_file="${DIR}/../templates/addon.template"
    rm -rf "${config_file}"
    cp -n "${template_file}" "${config_file}"

    $SED -i -e "s@##ID##@${name}@g" "${config_file}"
}

function build_ingress_controller_template() {
    domain="${1}"
    config_file="${REPO_ROOT}/ingress-controller.json"
    template_file="${DIR}/../templates/ingress-controller.template"
    rm -rf "${config_file}"
    cp -n "${template_file}" "${config_file}"

    $SED -i -e "s@##DOMAIN##@${domain}@g" "${config_file}"
}

function get_cluster_id() {
    name="${1}"
    id=$($OCM list clusters | $GREP "\s${name}\s" | cut -d' ' -f1)
    echo "${id}"
}

function get_credentials() {
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    id=$(get_cluster_id $CLUSTER_NAME)
    cred=$($OCM get /api/clusters_mgmt/v1/clusters/$id/credentials | jq .admin)
    echo $cred
}

function get_kube_admin_login() {

    cred=$(get_credentials)
    echo "oc login -u $(echo $cred | jq -r .user) -p $(echo $cred | jq -r .password) $(get_api_url)"
}

function get_api_url() {
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    api=$($OCM list clusters | $GREP "\s${CLUSTER_NAME}\s" | awk '{print $3}')
    echo $api
}

function install_addon() {
    name="${1}"
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    id=$(get_cluster_id $CLUSTER_NAME)
    build_addon_template "$name"
    $OCM post "/api/clusters_mgmt/v1/clusters/${id}/addons" --body "${REPO_ROOT}/addon-${name}.json"
}

function remove_addon() {
    name="${1}"
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    id=$(get_cluster_id $CLUSTER_NAME)
    build_addon_template "$name"
    $OCM delete "/api/clusters_mgmt/v1/clusters/${id}/addons/${name}"
}

function generate_kubeconfig() {
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    if [[ "${OUTPUT}" == "" ]]; then
        OUTPUT="${REPO_ROOT}/${CLUSTER_NAME}-config"
    fi
    cred=$(get_credentials)
    echo "KUBECONFIG=$OUTPUT oc login -u $(echo $cred | jq .user | sed -e s/\"//g) -p $(echo $cred | jq .password | sed -e s/\"//g) $(get_api_url)"
    for i in {1..20}; do
        if KUBECONFIG="${OUTPUT}" oc login -u $(echo $cred | jq .user | sed -e s/\"//g) -p $(echo $cred | jq .password | sed -e s/\"//g) $(get_api_url) --insecure-skip-tls-verify=true; then
            break
        else
            echo "[WARN] Login attempt $i/20 failed, waiting 1 minute before next try"
            sleep 60
        fi
    done
}

#########################################################
# Main
#########################################################

print_vars
OCM=$(which ocm)
if [ $? -gt 0 ]; then
    OCM="${REPO_ROOT}ocm"
    download_ocm
fi

$OCM whoami 2>/dev/null >/dev/null
if [ $? -gt 0 ]; then
    $OCM login --url=https://api.stage.openshift.com/ --token="${TOKEN}"
    $OCM whoami
fi

if [[ "${OPERATION}" == "get" ]]; then
    if [[ "${DATA_FOR_GET}" == "credentials" ]]; then
        get_credentials
    elif [ "${DATA_FOR_GET}" == "api_url" ]; then
        get_api_url
    elif [ "${DATA_FOR_GET}" == "kubeconfig" ]; then
        generate_kubeconfig
    elif [ "${DATA_FOR_GET}" == "kube_admin_login" ]; then
        get_kube_admin_login
    fi
    exit
fi

if [[ "${OPERATION}" == "set-storageclass" ]]; then
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    OUTPUT="${REPO_ROOT}temp-config"
    generate_kubeconfig
    KUBECONFIG="${OUTPUT}" oc apply -f "${REPO_ROOT}templates/storageclass.yaml"
    rm -rf "${OUTPUT}"
    exit
fi

if [[ "${OPERATION}" == "set-ingress-controller" ]]; then
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    OUTPUT="${REPO_ROOT}temp-config"
    generate_kubeconfig
    url=$(get_api_url)
    build_ingress_controller_template $(echo $url | sed -e 's#https://api.##g' | sed -e 's#:6443##g') $(get_api_url)
    KUBECONFIG="${OUTPUT}" oc apply -f "${REPO_ROOT}/ingress-controller.json"
    rm -rf "${OUTPUT}"
    exit
fi

if [[ "${OPERATION}" == "infra-pod-rebalance" ]]; then
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    OUTPUT="${REPO_ROOT}temp-config"
    generate_kubeconfig

    KUBECONFIG="${OUTPUT}" ${DIR}/infra-pod-rebalance.sh
    rm -rf "${OUTPUT}"
    exit
fi

if [[ "${OPERATION}" == "scale-count" ]]; then
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    id=$(get_cluster_id $CLUSTER_NAME)
    echo '{"nodes": { "compute": '${NODE_COUNT}'} }' | $OCM patch /api/clusters_mgmt/v1/clusters/$id
    exit
fi

if [[ "${OPERATION}" == "hibernate" ]]; then
    if [[ ${CLUSTER_NAME} == "" ]]; then
          CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    echo "Hibernate cluster ${CLUSTER_NAME}"
    $OCM hibernate cluster "$(get_cluster_id $CLUSTER_NAME)"
    exit
fi

if [[ "${OPERATION}" == "resume" ]]; then
    if [[ ${CLUSTER_NAME} == "" ]]; then
          CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    echo "Resume cluster ${CLUSTER_NAME}"
    $OCM resume cluster "$(get_cluster_id $CLUSTER_NAME)"
    exit
fi

if [[ "${OPERATION}" == "install-addon" ]]; then
    echo "Installing addon ${ADDON_ID}"
    install_addon "$ADDON_ID"
    exit
fi

if [[ "${OPERATION}" == "remove-addon" ]]; then
    echo "Removing addon ${ADDON_ID}"
    remove_addon "$ADDON_ID"
    exit
fi

if [[ "${OPERATION}" == "extend-expiration" ]]; then
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    id=$(get_cluster_id $CLUSTER_NAME)
    printf '{\n\t"expiration_timestamp": "%s"\n}\n' "$(${DATE} --iso-8601=seconds -d +${EXTEND_DAYS}\ days)" | $OCM patch /api/clusters_mgmt/v1/clusters/$id
    exit
fi

if [[ "${OPERATION}" == "create" ]]; then
    read_aws_csv

    if [[ "${CLUSTER_JSON}" == "" ]]; then
        echo "Cluster json is not provided -> going to build own with provided arguments"
        build_config_json
    fi

    RESPONSE=`$OCM post /api/clusters_mgmt/v1/clusters --body="${CLUSTER_JSON}" 2>&1`
    if [[ $? -ne 0 ]]; then
        ERROR_MESSAGE=$(echo $RESPONSE | jq -r .details[].Error_Key)
        if [[ "$ERROR_MESSAGE" != "DuplicateClusterName" ]]; then
            echo "Something went wrong when creating the cluster. Exit!!!!"
            echo $RESPONSE | jq .
            exit 1
        else
            echo "'${CLUSTER_NAME}' cluster already exists"
        fi
    fi
    echo $RESPONSE | jq .
    if [[ "${WAIT}" == "true" ]]; then
        wait_for_cluster_install
    fi
elif [[ "${OPERATION}" == "delete" ]]; then
    if [[ ${CLUSTER_NAME} == "" ]]; then
        CLUSTER_NAME=$(get_cluster_name_from_config)
    fi
    echo "Delete cluster ${CLUSTER_NAME}"
    $OCM delete "/api/clusters_mgmt/v1/clusters/$(get_cluster_id $CLUSTER_NAME)"
elif [[ "${WAIT}" == "true" ]]; then
    wait_for_cluster_install
else
    echo "No operation is specified, Exit!!!!"
    exit 1
fi
