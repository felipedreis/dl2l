#!/usr/bin/python
# -*- coding: UTF-8 -*-

from util import *


def avg_arousal(exp_dir, save_dir):
    frames = get_data_frames(exp_dir, 'arousalHistory')
    arousal_avg, arousal_err = avg_over_time(frames)

    ax = arousal_avg.plot(title=u'Média do arousal da fome e sono no tempo')
    ax.set_xlabel(u'Tempo (min)')
    ax.set_ylabel(u'Arousal')
    ax.legend(loc='center left', bbox_to_anchor=(1, .5))
    save_graphic(ax, save_dir + '/arousal.png')
    return ax
