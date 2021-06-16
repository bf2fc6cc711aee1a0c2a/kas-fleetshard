#!/usr/bin/env python3

import os
import sys
import json
import argparse
from pprint import pprint
import statistics

outlier_definition = 'aggregatedEndToEndLatency99pct'
outlier_threshold = 10000

def get_kafkas(args):
    kafkas = []
    for kafka_result in args.test_results:
        kafkas.append(json.load(open(kafka_result)))
    return kafkas

def find_outliers(kafkas):
    outliers = []

    for metrics in kafkas:
        if (metrics[outlier_definition] > outlier_threshold):
            outliers.append(metrics['workload'].replace('Kafka Cluster: ',''))

    print('\nOutliers:', len(outliers))
    print('Kafkas:  ', len(kafkas))

def aggregate_ouput_metrics(kafkas, remove_outliers=True):
    aggregatedEndToEndLatencyAvg = []
    aggregatedEndToEndLatency99pct = []

    for metrics in kafkas:
        if ((metrics[outlier_definition] > outlier_threshold) and (remove_outliers)):
            continue
        aggregatedEndToEndLatencyAvg.append(metrics['aggregatedEndToEndLatencyAvg'])
        aggregatedEndToEndLatency99pct.append(metrics['aggregatedEndToEndLatency99pct'])

    if (aggregatedEndToEndLatencyAvg):
        print('Avg aggregatedEndToEndLatencyAvg:', statistics.mean(aggregatedEndToEndLatencyAvg))
        print('Avg aggregatedEndToEndLatency99pct:', statistics.mean(aggregatedEndToEndLatency99pct))
    else:
        print('No kafkas available')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('test_results', nargs='+', help='test result json files')
    args = parser.parse_args()

    kafkas = get_kafkas(args)

    find_outliers(kafkas)

    print('\nWITH outliers:')
    aggregate_ouput_metrics(kafkas, remove_outliers=False)
    print('\nWITHOUT outliers:')
    aggregate_ouput_metrics(kafkas, remove_outliers=True)
