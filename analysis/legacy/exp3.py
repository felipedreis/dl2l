#!/usr/bin/python
# -*- coding: UTF-8 -*-

from util import *
from avgAccumulatedNutrients import *
from avgBehaviouralEfficiency import *
from avgExchangedStimuli import *
from avgAccumulatedChoices import *

import pandas as pd
import matplotlib.pyplot as plt
from scipy.stats import shapiro

wd = '/media/felipe/0c5b2979-59fc-4555-a2d9-88e99846e8f1/'

experiments = [(2, 1), (3, 2), (3, 3), (3, 4), (3, 5)]
lifetimes = list()
eaten_nutrients = list()
data = []
for exp in experiments:
    exp_name = "exp_%d_%d" % exp
    dest_dir = "results/exp_3/%d" % exp[1]

    #avg_accumulated_nutrients(wd + exp_name, dest_dir)
    #avg_behavioural_eff(wd + exp_name, dest_dir)
    #avg_accumulated_choices(wd + exp_name, dest_dir)

    exs_avg, exs_err, _, _ = avg_exchanged_stimuli(wd + exp_name, dest_dir)

    lt_df, eaten_df, dist_df = get_data_frames(wd + exp_name, 'lifetimes'), \
                               get_data_frames(wd + exp_name, 'eatenNutrients'), \
                               get_data_frames(wd + exp_name, 'distances')
    lt_df, eaten_df, dist_df = pd.concat(lt_df), pd.concat(eaten_df), pd.concat(dist_df)
    lt_df, eaten_df, dist_df = lt_df['lifetime']/60, eaten_df['eatenNutrients'], dist_df['distances']

    lt_mean, eaten_mean, dist_mean = lt_df.mean(), eaten_df.mean(), dist_df.mean()
    lt_error, eaten_error, dist_error = mean_error(lt_df), mean_error(eaten_df), mean_error(dist_df)

    (_,lt_pv), (_,eaten_pv), (_,dist_pv) = shapiro(lt_df), shapiro(eaten_df), shapiro(dist_df)

    data.append((exp[1], lt_mean, lt_error, lt_pv, dist_mean, dist_error, dist_pv, eaten_mean, eaten_error, eaten_pv))

resume = pd.DataFrame(data=data, columns=[u'Criaturas',u'Tempo de vida', u'ER tempo de vida', u'p-valor',
                                          u'Distância  percorrida',u'ER distância percorrida', u'p-valor',
                                          u'Nutrientes comidos', u'ER nutrientes comidos', u'p-valor'])
resume = resume.set_index(u'Criaturas')
print resume.to_csv(encoding='utf-8')
