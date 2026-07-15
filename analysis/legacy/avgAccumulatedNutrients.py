#!/usr/bin/python
# -*- coding: UTF-8 -*-

import matplotlib.pyplot as plt
from util import *


def avg_accumulated_nutrients(exp_dir, save_dir):
    rnd.seed(0)
    eaten_nutrients = get_data_frames(exp_dir, 'eatenNutrientsOverTime')
    eaten_nutrients_avg, eaten_nutrients_error = avg_over_time(eaten_nutrients)

    ax = eaten_nutrients_avg.plot(title=u'Média da soma acumulada de nutrientes comidos no tempo', linewidth=2)
    ax.set_xlabel(u'Tempo (min)')
    ax.set_ylabel(u'Soma acumulada de nutrientes comidos')

    lines = ax.get_lines()
    line_styles = get_line_styles(len(lines))
    for (i, line) in enumerate(lines):
        line.set_linestyle(line_styles[i])

    ax.legend(loc='center left', bbox_to_anchor=(1, .5))
    save_graphic(ax, save_dir + '/accumulatedNutrients.png')

    bx = eaten_nutrients_error.plot(title=u'Erro relativo da soma acumulada de nutrientes comidos', linewidth=2)
    bx.set_xlabel(u'Tempo (min)')
    bx.set_ylabel(u'Erro relativo')

    lines = bx.get_lines()
    for (i, line) in enumerate(lines):
        line.set_linestyle(line_styles[i])

    bx.legend(loc='center left', bbox_to_anchor=(1, .5))
    save_graphic(bx, save_dir + '/accumulatedNutrients_err.png')

    return ax