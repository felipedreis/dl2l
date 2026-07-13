#!/usr/bin/python
# -*- coding: UTF-8 -*-

import matplotlib.pyplot as plt
from util import *


def avg_behavioural_eff(exp_dir, save_dir):
    rnd.seed(0)
    behavioural_eff_frames = get_data_frames(exp_dir, 'behaviouralEfficiency')
    beh_eff_avg, beh_eff_error = avg_over_time(behavioural_eff_frames)

    ax = beh_eff_avg.plot(title=u'Média da eficiência comportamental\n para tarefas simples e complexas no tempo', linewidth=1)
    ax.set_xlabel(u'Tempo (min.)')
    ax.set_ylabel(u'Média da eficiência comportamental')
    lines = ax.get_lines()
    styles = get_line_styles(len(lines))
    for (i, line) in enumerate(lines):
        line.set_linestyle(styles[i])

    ax.legend(loc='center left', bbox_to_anchor=(1, .5))
    save_graphic(ax, save_dir + '/behaviouralEfficiency.png')

    bx = beh_eff_error.plot(title=u'Erro relativo da eficiência comportamental\n para tarefas simples e complexas no tempo', linewidth=1)
    bx.set_xlabel(u'Tempo (min.)')
    bx.set_ylabel(u'Erro relativo')
    bx.legend(loc='center left', bbox_to_anchor=(1, .5))
    lines = bx.get_lines()
    for (i, line) in enumerate(lines):
        line.set_linestyle(styles[i])

    save_graphic(bx, save_dir + '/behaviouralEfficiency_err.png')

    return ax