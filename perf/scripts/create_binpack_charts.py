#!/usr/bin/env python3

import os
import sys
import json
import pygal
import argparse
from itertools import chain
from datetime import datetime
from format_html_output import *

class AggregateWorkload:
    def __init__(self, workload, message_size):
        self.message_size = message_size
        self.workload = workload
        self.throughputTotal = 0
        self.throughputSamples = 0
        self.throughputSums = []
        self.overallAverageThroughput = 0

    def aggregate(self, results):
        init = [float(0)] * (len(results) - len(self.throughputSums))
        if len(init):
            self.throughputSums.extend(init)
        for i, r in enumerate(results):
            rate = (float(r) * self.message_size) / 1024 / 1024
            # print(i, rate, self.throughputSums)
            self.throughputSums[i] += rate
            self.throughputTotal += rate
            self.throughputSamples += 1

    def overall_average_throughput(self):
        return self.throughputTotal / self.throughputSamples

    def average_throughput(self):
        sums_ = [(tot / (self.throughputSamples / len(self.throughputSums))) for tot in self.throughputSums ]
        print(sums_)
        return sums_

def create_charts(args, message_size):
    test_results = args.test_results
    workload_results = {}
    test_metadata = {}

    if args.metadata_file is not None:
        test_metadata = json.load(open(args.metadata_file))

    for test_result in test_results:
        result = json.load(open(test_result))
        if not result['workload'] in workload_results:
            workload_results[result['workload']] = []
        workload_results[result['workload']].append(result)

    publish_aggregate_workloads = {}
    consume_aggregate_workloads = {}
    for workload, results in workload_results.items():
        publish_aggregate_workloads[workload] = AggregateWorkload(workload, message_size)
        consume_aggregate_workloads[workload] = AggregateWorkload(workload, message_size)
        for x in results:
            publish_aggregate_workloads[workload].aggregate(x["publishRate"])
            consume_aggregate_workloads[workload].aggregate(x["consumeRate"])

    per_cluster_chart = pygal.Bar()
    per_cluster_chart.title = args.title_pattern % 'Average Publish Rate Per Kafka Instance'
    x_labels = []
    y_values = []
    for workload in sorted(publish_aggregate_workloads):
        aggregate = publish_aggregate_workloads[workload]
        x_labels.append(workload)
        y_values.append(aggregate.overall_average_throughput())

    per_cluster_chart.x_labels = x_labels
    per_cluster_chart.add(("Message Size %d" % (message_size)), y_values)
    per_cluster_chart.human_readable = True
    per_cluster_chart.y_title = "Mi/second"

    write_chart(per_cluster_chart, test_metadata)

    publish_rate_by_instance_chart = aggregate_chart(publish_aggregate_workloads, args.title_pattern % 'Average Publish Rate Per Kafka Instance Over Time')
    write_chart(publish_rate_by_instance_chart, test_metadata)

    consume_rate_by_instance_chart = aggregate_chart(consume_aggregate_workloads, args.title_pattern % 'Average Consume Rate Per Kafka Instance Over Time')
    write_chart(consume_rate_by_instance_chart, test_metadata)


def aggregate_chart(aggregate_workload, title):
    publish_rate_by_instance_chart = pygal.XY(style=pygal.style.LightColorizedStyle,
                                              show_dots=True,
                                              dots_size=.03,
                                              legend_at_bottom=False,
                                              truncate_legend=10,
                                              include_x_axis=True
                                              )
    publish_rate_by_instance_chart.title = title
    publish_rate_by_instance_chart.human_readable = True
    publish_rate_by_instance_chart.y_title = 'Mi/second'
    publish_rate_by_instance_chart.x_title = 'Time (seconds)'
    for workload in sorted(aggregate_workload):
        aggregate = aggregate_workload[workload]
        publish_rate_by_instance_chart.add(workload, [(10 * x, y) for x, y in enumerate(aggregate.average_throughput())])
    return publish_rate_by_instance_chart

def create_dir(dir):
    if not os.path.exists(dir):
        os.makedirs(dir)

def get_charts_path():
    current_dir = datetime.now().strftime('%Y-%m-%d_%H-%M-%S') + '/'
    output_dir = 'output_charts/'
    create_dir(output_dir)
    create_dir(output_dir + current_dir)
    return output_dir + current_dir

def write_chart(chart, metadata):
    global charts_path
    chart.render_to_file(charts_path + '%s.svg' % (chart.title))
    try:
        chart.render_to_png(charts_path + '%s.png' % (chart.title))
    except ModuleNotFoundError:
        pass

    if args.metadata_file is not None:
        print_to_html(chart.title, chart.render_data_uri(), metadata)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Produces bin packed charts')
    parser.add_argument('test_results', nargs='+', help='test result json file(s)')
    parser.add_argument('--title-pattern', dest='title_pattern', default='%s', help='pattern used to form chart title')
    parser.add_argument('--metadata-file', dest='metadata_file', help='file containing test metadata')

    charts_path = get_charts_path()
    args = parser.parse_args()
    create_charts(args, 1024)
    print('Output graphs:', charts_path)
