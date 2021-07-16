#!/usr/bin/env python3

import os
import sys
import json
import pygal
import argparse
import glob
from datetime import datetime
from itertools import chain
from format_html_output import *
from pprint import pprint

def create_charts(args):
    results = []
    test_metadata = {}

    if len(args.test_results) == 1 and os.path.isdir(args.test_results[0]) :
        files = glob.glob(args.test_results[0]+'/**/result*.json', recursive = True)
    else :
        files = args.test_results

    for test_result in files:
        result = json.load(open(test_result))
        results.append(result)

        if args.metadata_file is not None:
            test_metadata = json.load(open(args.metadata_file))

    create_chart(args.title_pattern % 'Publish latency 99pct',
                 y_label='Latency (ms)',
                 time_series=[(x['workload'], x['publishLatency99pct']) for x in results],
                 metadata=test_metadata)

    create_chart(args.title_pattern % 'Publish rate',
                 y_label='Rate (msg/s)',
                 time_series=[(x['workload'], x['publishRate']) for x in results],
                 metadata=test_metadata)

    create_chart(args.title_pattern % 'Consume rate',
                 y_label='Rate (msg/s)',
                 time_series=[(x['workload'], x['consumeRate']) for x in results],
                 metadata=test_metadata)

    create_chart(args.title_pattern % 'End To End Latency Avg',
                 y_label='LatencyAvg msec',
                 time_series=[(x['workload'], x['endToEndLatencyAvg']) for x in results],
                 metadata=test_metadata)

    #create_chart(args.title_pattern % 'Backlog',
    #             y_label='Bocklog (msg)',
    #             time_series=[(x['workload'], x['backlog']) for x in results])

    create_quantile_chart(args.title_pattern % 'Publish latency quantiles',
                          y_label='Latency (ms)',
                          time_series=[(x['workload'], x['aggregatedPublishLatencyQuantiles']) for x in results],
                          metadata=test_metadata)

    create_quantile_chart(args.title_pattern % 'End To End latency quantiles',
                          y_label='Latency (ms)',
                          time_series=[(x['workload'], x['aggregatedEndToEndLatencyQuantiles']) for x in results],
                          metadata=test_metadata)

def create_chart(title, y_label, time_series, metadata):

    chart = pygal.XY(style=pygal.style.LightColorizedStyle,
                     show_dots=True,
                     dots_size=.03,
                     legend_at_bottom=False,
                     truncate_legend=10,
                    )
    chart.title = title

    chart.human_readable = True
    chart.y_title = y_label
    chart.x_title = 'Time (seconds)'
    # line_chart.x_labels = [str(10 * x) for x in range(len(time_series[0][1]))]

    for label, values in time_series:
        chart.add(label, [(10*x, y) for x, y in enumerate(values)])

    chart.range = (0, max(chain(* [l for (x, l) in time_series])) * 1.20)
    write_chart(chart, metadata)

def create_quantile_chart(title, y_label, time_series, metadata):
    import math
    chart = pygal.XY(style=pygal.style.LightColorizedStyle,
                     fill=False,
                     legend_at_bottom=False,
                     truncate_legend=10,
                     x_value_formatter=lambda x: '{} %'.format(100.0 - (100.0 / (10**x))),
                     show_dots=True,
                     dots_size=.3,
                     show_x_guides=True)
    chart.title = title
    # chart.stroke = False

    chart.human_readable = True
    chart.y_title = y_label
    chart.x_title = 'Percentile'
    chart.x_labels = [1, 2, 3, 4, 5]

    for label, values in time_series:
        values = sorted((float(x), y) for x, y in values.items())
        xy_values = [(math.log10(100 / (100 - x)), y) for x, y in values if x <= 99.999]
        chart.add(label, xy_values)

    write_chart(chart, metadata)

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
    parser = argparse.ArgumentParser(description='Produces charts')
    parser.add_argument('test_results', nargs='+', help='test result json file(s)')
    parser.add_argument('--title-pattern', dest='title_pattern', default='%s', help='pattern used to form chart title')
    parser.add_argument('--metadata-file', dest='metadata_file', help='file containing test metadata')

    charts_path = get_charts_path()
    args = parser.parse_args()
    create_charts(args)
    print('Output graphs:', charts_path)
