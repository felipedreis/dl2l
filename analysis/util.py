
import numpy as np
import numpy.random as rnd
import pandas as pd
import os
import matplotlib.pyplot as plt

Z = 1.96

MILLIS_TO_HOURS = 2.777777778e-7
MILLIS_TO_MINUTES = 1.666666667e-5
MILLIS_TO_SECONDS = 1.000000000e-3

line_styles = [
    '-',
    '--',
    '-.',
    ':',
]

line_markers = [
    '.',
    ',',
    'o',
    'v',
]


def random_color():
    return tuple(rnd.randint(255, size=3))


def get_line_style():
    return line_styles[rnd.randint(len(line_styles))]


def get_line_styles(n):
    styles = []
    for i in range(0, n):
        styles.append(get_line_style())
    return styles


def get_marker():
    return line_markers[rnd.randint(len(line_markers))]


def get_markers(n):
    markers = []
    for i in range(0, n):
        markers.append(get_marker())
    return markers


def get_data_frames(working_dir, name):
    csv_files = []
    name_ext = name + '.csv'
    for dir, _, files in os.walk(working_dir):
        if name_ext in files:
            csv_files.append(dir + '/' + name_ext)

    data_frames = [pd.read_csv(f) for f in csv_files]
    return data_frames


def avg_over_time(frames):
    concatenated_frames = pd.concat(frames)
    grouped = concatenated_frames.groupby('time')
    avg = grouped.mean()
    std = grouped.std()
    cnt = grouped.count()

    error = ((Z * std) / np.sqrt(cnt)) / avg
    return avg, error


def mean_error(l):
    return ((np.std(l) * Z) / np.sqrt(len(l))) / np.mean(l)


def error(l):
    return (np.std(l) * Z) / np.sqrt(len(l))


def save_graphic(ax, name):
    figure = ax.get_figure()
    figure.savefig(name, bbox_inches='tight')
    plt.close(figure)

