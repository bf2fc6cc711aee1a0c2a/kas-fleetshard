# mk-performance-tests

This repository contains a set of performance tests to primarily evaluate Kafka performance on OpenShift Dedicated.

The primary goal is to be able to run these tests locally against multiple OSD clusters.

## Requirements
* java openjdk 11
* maven > 3.3.1
* oc client > 4.5.13
* jq (only for [osd-provision.sh](scripts/osd-provision.sh))
* go > 1.13 (only for build remote-write tool)

## Obtaining the OSD clusters
Normally, we use an OSD cluster for the kafka instances, and a separate OSD cluster for the test workers.  
However, it is also possible co-deploy to a single cluster if desired.

Create clusters like this:

```
AWS_ID=<account id>
AWS_SEC_CREDENTIALS_FILE=<osdCcsAdmin_accessKeys.csv>

./scripts/osd-provision.sh --create  --aws-sec-credentials-file ${AWS_SEC_CREDENTIALS_FILE} --aws-accout-id ${AWS_ID} \
    --name ${USER}-kafka --region us-east-1 --flavor r5.4xlarge --count 5 # kafka
./scripts/osd-provision.sh --create  --aws-sec-credentials-file ${AWS_SEC_CREDENTIALS_FILE} --aws-accout-id ${AWS_ID} \
    --name ${USER}-client --region us-east-1 --flavor r5.2xlarge --count 4 # clients
```

## Rebalancing Infra Pods
It is recommended to complete this step before proceeding with other commands. 
It helps to prevent an issue where the infra nodes get into a NotReady state. 
See [OHSS-2174](https://issues.redhat.com/browse/OHSS-2174) for more details.

```
./scripts/osd-provision.sh --infra-pod-rebalance --name ${USER}-kafka
```

## Getting Cluster Admin
Once cluster creation finishes, get the cluster admin.

```
./scripts/osd-provision.sh --get credentials  --name ${USER}-kafka --region us-east-1
```

## Setting up test contexts
The following creates the test contexts expected by the tests.

```
./scripts/osd-provision.sh --get kubeconfig  --name ${USER}-client --region us-east-1 --output client-config
./scripts/osd-provision.sh --get kubeconfig  --name ${USER}-kafka --region us-east-1 --output kafka-config
```

You can make use of these files interactively too.

```
 oc --kubeconfig client-config get pod --all-namespaces -o wide
```

## Getting submodule
**Make sure you update submodules!**

You need to use a personal access token from GitHub developer settings when prompted for password.
```
make submodule-init
```

## openmessaging-benchmark

Until we find a more suitable place, the benchmark jars have been published into the makeshift [github maven repo](https://github.com/shawkins/repo).

If changes are needed to the openmessaging-benchmark, that will require updating that project and temp repo, or creating another one.

The temp repo is simply a copy of the contents of ~/.m2/repository/io/openmessaging after running `mvn clean install -DcreateChecksum=true` in [the project](https://github.com/lulf/openmessaging-benchmark).

## Preparing thanos writer [optional]
```
make rw-install
```

## Providing Fleetshard Images / Deployment Artifacts

Re-use of systemtest means that the fleetshard component install will be based upon your local build.  Typically you will want to build/push prod images.  From the parent directory / fleetshard root run:

```
mvn clean -Pquickly package -pl operator,sync \
-Dquarkus.container-image.registry=quay.io \
-Dquarkus.container-image.group=${USER} \
-Dquarkus.container-image.tag=latest \
-Dquarkus.container-image.build=true \
-Dquarkus.container-image.push=true
```

## Running tests
Assuming that the kubeconfigs are stored in the root folder of this repository, running the maven tests from the command
line or from the IDE should work:
```
mvn test -Dtest=ManagedKafkaValueProdMinimumTest -DskipTests=false
```

Or select test by tag:
* ci
* perf
* fault
```
mvn test -Dtest=ManagedKafkaValueProdMinimumTest -Pperf
```

Grafana dashboards are imported into namespace `managed-services-monitoring-grafana`, for accessing grafana
route type
```
KUBECONFIG=kafka-config oc get route grafana-route -n managed-services-monitoring-grafana -o=jsonpath='{.spec.host}'
```

## Environment variables
| Name | Description | Default value |
|-|:-:|-:|
| LOG_DIR | Path where test suite stores logs from failed tests etc. | $(pwd)/target/logs |
| CONFIG_PATH | Path where is stored config.json with env variables and values. | $(pwd)/config.json |
| OMB_TEST_DURATION | Specifies the length of the test. | PT1M |
| OMB_KUBECONFIG | Kubeconfig with connection for OMB cluster. | $(pwd)/client-config |
| KAFKA_KUBECONFIG | Kubeconfig with connection for KAFKA cluster. | $(pwd)/kafka-config |
| REMOTE_WRITE_DIR | Path where Thanos remote write is stored. | $(pwd)/remote-write |
| MONITORING_STUFF_DIR | Path where monitoring repo is stored. | $(pwd)/kafka-monitoring-stuff |
| STRIMZI_INSTALL_MODE | Mode for Kafka instances [CLUSTER, NAMESPACE]. Namespace is deprecated. | CLUSTER |
| THANOS_URL | URL for remote write Thanos. | null |
| THANOS_TOKEN | Token for remote write. | null |
| OBSERVATORIUM_ROUTE | Route for sending logs and metrics. | null |
| HISTOGRAM_NUMBER_OF_SIGNIFICANT_VALUE_DIGITS | Controls the number of significant digits used when building the latency histograms. <br> Use to prevent excessive memory use especially when running longer tests. | PT1M |
| MAX_KAFKA_INSTANCES | Maximum number of Kafka instances that will be deployed by tests that use the deploy until full approach. | max int |
| NUM_INGRESS_CONTROLLERS | Number of IngressControllers to split Kafka instances across. | 1 |
| PROVIDED_KAFKA_CLUSTERS_FILE | File containing a list of Kafka bootstrap URLs, one per line. This is only used for decoupled tests. | provided_clusters.yaml |
| STRIMZI_VERSION | Strimzi version to use. | pom.xml/properties/strimzi.version |
| TARGET_RATE | Number of records for each producer to send per second | 2000 |
| WORKERS_PER_INSTANCE | Number of workers per Kafka instance | 2 |
| TOPICS_PER_KAFKA | Number of topics per Kafka instance | 1 |
| PRODUCERS_PER_TOPIC | Number of producers per topic | 1 |
| PAYLOAD_FILE_SIZE | Size of the payload file to use for records as a value that can be parsed as a [Quantity](https://www.javadoc.io/doc/io.fabric8/kubernetes-model/latest/io/fabric8/kubernetes/api/model/Quantity.html) | 1Ki |

## Creating charts
There are currently two scripts for generating charts.

The `scripts/create_charts.py` script will produce timeseries graphs of throughput and latencies based on workload names. 
If your test generates multiple results for different workloads, you can create a chart by running:
```
./scripts/create_charts.py result1.json result2.json ... resultN.json
```

The chart's title can be augmented by using the `--title-pattern` argument.
```
./scripts/create_charts.py --title-pattern='Varying Kafka Mem - %s - 40MBsec_inout 250p250c' result1.json result2.json ... resultN.json
```

The `scripts/create_binpack_charts.py` script creates a bar chart and was written to display results from binpacking tests where each bar would correspond 
to a specific number of Kafka instances, and the height of the bar would indicate the average throughput of each instance in the system (in MB/second). 
This script will convert messages/sec to MB/sec based on a fixed message size (currently hardcoded int he script). 
You can create a chart by running `./scripts/create_binpack_charts.py result1.json result2.json ... resultN.json`.

## Maintainers
* David Kornel <dkornel@redhat.com>
* Keith Wall <kwall@redhat.com>
