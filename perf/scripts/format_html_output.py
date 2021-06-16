#!/usr/bin/env python3

import json

def append_cells(obj, key):
    return ''.join(["<td>"+x[key]+"</td>" for x in obj])

def append_cells2(obj, key, key2):
    return ''.join(["<td>"+x[key][key2]+"</td>" for x in obj])

def append_int_cells(obj, key):
    return ''.join(["<td>"+str(x[key])+"</td>" for x in obj])

def append_int_cells2(obj, key, key2):
    return ''.join(["<td>"+str(x[key][key2])+"</td>" for x in obj])

def append_json_cells(obj, key):
    return ''.join(["<td>"+json.dumps(x[key])+"</td>" for x in obj])

def print_to_html(title_text, data_uri, metadata):
    myfile = open('%s.html' % (title_text), 'w')
    html =  """<html>
        <head><title>{title}</title></head>
        <body>
        <img width="500" height="500" alt="{title}" src="{uri}"</img>
        <h4>Kafka</h4>
        <table border='1' style='border-collapse:collapse'>
          <tr><td>Count of Nodes</td><td>{countOfNodes}</td></tr>
          <tr><td>Count of Workers</td><td>{countOfWorkers}</td></tr>
          <tr><td>Worker Instance Type</td><td>{workerInstanceType}</td></tr>
          <tr><td>Infra Instance Type</td><td>{infraInstanceType}</td></tr>
          <tr><td>Cluster Name</td>{names}</tr>
          <tr><td>Broker Count</td>{brokerCount}</tr>
          <tr><td>Zookeeper Count</td>{zookeeperCount}</tr>
          <tr><td>Kafka Config</td>{kafkaConfig}</tr>
          <tr><td>Kafka Request Resources</td>{kafkaRequestResources}</tr>
          <tr><td>Kafka Limit Resources</td>{kafkaLimitResources}</tr>
          <tr><td>Zookeeper Request Resource</td>{zookeeperRequestResources}</tr>
          <tr><td>Zookeeper Limit Resources</td>{zookeeperLimitResources}</tr>
          <tr><td>Kafka JvmOps</td>{kafkaJvmOps}</tr>
          <tr><td>Zookeeper JvmOpts</td>{zookeeperJvmOps}</tr>
        </table>
        <h4>Workers</h4>
        <table border='1' style='border-collapse:collapse'>
          <tr><td>Count of Nodes</td><td>{clientCountOfNodes}</td></tr>
          <tr><td>Count of Workers</td><td>{clientCountOfWorkers}</td></tr>
          <tr><td>Worker Instance Type</td><td>{clientWorkerInstanceType}</td></tr>
          <tr><td>Infra Instance Type</td><td>{clientInfraInstanceType}</td></tr>
          <tr><td>Name</td>{workloadName}</tr>
          <tr><td>Worker Count</td>{workerCount}</tr>
          <tr><td>Topics</td>{workloadTopics}</tr>
          <tr><td>Partitions Per Topic</td>{workloadPartitionsPerTopic}</tr>
          <tr><td>Message Size</td>{workloadMessageSize}</tr>
          <tr><td>Subscriptions Per Topic</td>{workloadSubscriptionsPerTopic}</tr>
          <tr><td>Consumers Per Subscription</td>{workloadConsumerPerSubscription}</tr>
          <tr><td>Producers Per Topic</td>{workloadProducersPerTopic}</tr>
          <tr><td>Producer Rate</td>{workloadProducerRate}</tr>
          <tr><td>Consumer Backlog Size GB</td>{workloadConsumerBacklogSizeGB}</tr>
          <tr><td>Test Duration Minutes</td>{workloadTestDurationMinutes}</tr>
        </table>
        </body>
        </html>"""

    myfile.write(html.format(
        uri = data_uri,
        title = title_text,
        countOfNodes = metadata['openshift-clusters']['kafka-env']['countOfNodes'],
        countOfWorkers = metadata['openshift-clusters']['kafka-env']['countOfWorkers'],
        workerInstanceType = metadata['openshift-clusters']['kafka-env']['workerInstanceType'],
        infraInstanceType = metadata['openshift-clusters']['kafka-env']['infraInstanceType'],
        names=append_cells(metadata['kafka-clusters'], 'name'),
        brokerCount=append_int_cells(metadata['kafka-clusters'], 'brokerCount'),
        zookeeperCount=append_int_cells(metadata['kafka-clusters'], 'zookeeperCount'),
        kafkaConfig=append_json_cells(metadata['kafka-clusters'], 'kafkaConfig'),
        kafkaRequestResources=append_json_cells(metadata['kafka-clusters'], 'kafkaRequestResources'),
        kafkaLimitResources=append_json_cells(metadata['kafka-clusters'], 'kafkaLimitResources'),
        zookeeperRequestResources=append_json_cells(metadata['kafka-clusters'], 'zookeeperRequestResources'),
        zookeeperLimitResources=append_json_cells(metadata['kafka-clusters'], 'zookeeperLimitResources'),
        kafkaJvmOps=append_json_cells(metadata['kafka-clusters'], 'kafkaJvmOps'),
        zookeeperJvmOps=append_json_cells(metadata['kafka-clusters'], 'zookeeperJvmOps'),

        clientCountOfNodes = metadata['openshift-clusters']['clients-env']['countOfNodes'],
        clientCountOfWorkers = metadata['openshift-clusters']['clients-env']['countOfWorkers'],
        clientWorkerInstanceType = metadata['openshift-clusters']['clients-env']['workerInstanceType'],
        clientInfraInstanceType = metadata['openshift-clusters']['clients-env']['infraInstanceType'],
        workloadName=append_cells2(metadata['workers'], 'workload', 'name'),
        workerCount=append_int_cells(metadata['workers'], 'workerCount'),
        workloadTopics=append_int_cells2(metadata['workers'], 'workload', 'topics'),
        workloadPartitionsPerTopic=append_int_cells2(metadata['workers'], 'workload', 'partitionsPerTopic'),
        workloadMessageSize=append_int_cells2(metadata['workers'], 'workload', 'messageSize'),
        workloadSubscriptionsPerTopic=append_int_cells2(metadata['workers'], 'workload', 'subscriptionsPerTopic'),
        workloadConsumerPerSubscription=append_int_cells2(metadata['workers'], 'workload', 'consumerPerSubscription'),
        workloadProducersPerTopic=append_int_cells2(metadata['workers'], 'workload', 'producersPerTopic'),
        workloadProducerRate=append_int_cells2(metadata['workers'], 'workload', 'producerRate'),
        workloadConsumerBacklogSizeGB=append_int_cells2(metadata['workers'], 'workload', 'consumerBacklogSizeGB'),
        workloadTestDurationMinutes=append_int_cells2(metadata['workers'], 'workload', 'testDurationMinutes')
        ))
    myfile.close()

