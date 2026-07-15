#!/usr/bin/python
# -*- coding: UTF-8 -*-

import matplotlib.pyplot as plt
from util import *


def avg_accumulated_choices(exp_dir, save_dir):
    rnd.seed(0)
    accumulated_choices_frames = get_data_frames(exp_dir, 'accumulatedChoicesOverTime')
    acc_choices_avg, acc_choices_error = avg_over_time(accumulated_choices_frames)

    ax = acc_choices_avg.plot(title=u'Média  das escolhas acumulada no tempo por mecanismo', linewidth=2.5)
    ax.set_xlabel(u'Tempo (min.)')
    ax.set_ylabel(u'Escolhas acumuladas')
    lines = ax.get_lines()
    styles = get_line_styles(len(lines))
    for (i, line) in enumerate(lines):
        line.set_linestyle(styles[i])

    ax.legend(loc='center left', bbox_to_anchor=(1, .5))
    save_graphic(ax, save_dir + '/accumulatedChoices.png')

    bx = acc_choices_error.plot(title=u'Erro relativo das escolhas acumulada no tempo', linewidth=2.5)
    bx.set_xlabel(u'Tempo (min.)')
    bx.set_ylabel(u'Erro relativo')

    lines = bx.get_lines()
    for (i, line) in enumerate(lines):
        line.set_linestyle(styles[i])

    bx.legend(loc='center left', bbox_to_anchor=(1, .5))
    save_graphic(bx, save_dir + '/accumulatedChoices_err.png')

    return ax
