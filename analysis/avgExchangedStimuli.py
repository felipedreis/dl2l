#!/usr/bin/python
# -*- coding: UTF-8 -*-

import matplotlib.pyplot as plt
from util import *


def avg_exchanged_stimuli(exp_dir, save_dir):
    rnd.seed(0)

    produced_stimuli_frames = get_data_frames(exp_dir, 'producedStimuliGroupedOverTime')
    ps_avg, ps_error = avg_over_time(produced_stimuli_frames)

    ax = ps_avg.plot(title=u'Média dos estímulos trocados no tempo', linewidth=1.5)

    columns = len(ax.get_lines())
    styles = get_line_styles(columns)
    markers = get_markers(columns)
    i = 0
    for line in ax.get_lines():
        line.set_linestyle(styles[i])
        line.set_marker(markers[i])
        i += 1

    ax.set_xlabel(u'Tempo (min.)')
    ax.set_ylabel(u'Média de estímulos trocados')
    ax.legend(loc='center left', bbox_to_anchor=(1, .5))
    save_graphic(ax, save_dir + '/avgExchangedStimuliOverTime.png')

    bx = ps_error.plot(title=u'Erro relativo dos estímulos trocados no tempo', linewidth=1.5)
    i = 0
    for line in bx.get_lines():
        line.set_linestyle(styles[i])
        line.set_marker(markers[i])
        i += 1

    bx.set_xlabel(u'Tempo (min.)')
    bx.set_ylabel(u'Erro relativo')
    bx.legend(loc='center left', bbox_to_anchor=(1, .5))
    save_graphic(bx, save_dir + '/avgExchangedStimuliOverTime_err.png')

    return ps_avg, ps_error, ax, bx
