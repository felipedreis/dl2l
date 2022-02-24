#!/usr/bin/python
# -*- coding: UTF-8 -*-

from util import *
from avgAccumulatedNutrients import *
from avgBehaviouralEfficiency import *
from avgExchangedStimuli import  *

import pandas as pd
import matplotlib.pyplot as plt

wd = '/media/felipe/0c5b2979-59fc-4555-a2d9-88e99846e8f1/'

experiments = [(2, 1), (2, 2), (2, 3), (2, 4), (2, 5)]
avg_exch_stimuli = list()

error_limit = 0.1

for exp in experiments:
    exp_name = 'exp_%d_%d' % exp
    dest_dir = 'results/exp_2/%d' % exp[1]

    avg_accumulated_nutrients(wd + exp_name, dest_dir)
    avg_behavioural_eff(wd + exp_name, dest_dir)
    avg_exchanged_stimuli(wd + exp_name, dest_dir)

    produced_stimuli_frames = get_data_frames(wd + exp_name, 'producedStimuliGroupedOverTime')
    lifetimes_frames = get_data_frames(wd + exp_name, 'lifetimes')
    eaten_frames = get_data_frames(wd + exp_name, 'eatenNutrients')
    dist_frames = get_data_frames(wd + exp_name, 'distances')

    ps_avg, ps_error = avg_over_time(produced_stimuli_frames)
    adrenergic_error = ps_error['AdrenergicStimulus']
    adrenergic_series = ps_avg['AdrenergicStimulus']
    indexes = adrenergic_error[adrenergic_error < error_limit].index
    adrenergic_series = adrenergic_series[indexes]
    adrenergic_error = adrenergic_error[indexes]

    lt, eaten, dist = pd.concat(lifetimes_frames)['lifetime']/60.0, pd.concat(eaten_frames)['eatenNutrients'], pd.concat(dist_frames)['distances']
    lt_mean, eaten_mean, dist_mean = lt.mean(), eaten.mean(), dist.mean()
    lt_error, eaten_error, dist_error = mean_error(lt), mean_error(eaten), mean_error(dist)

    avg_exch_stimuli.append((exp[1], lt_mean, lt_error * 100, dist_mean, dist_error * 100, eaten_mean, eaten_error * 100))

resume = pd.DataFrame(data=avg_exch_stimuli, columns=[u'Criaturas', u'Média tempo de vida ', u'ER tempo de vida',
                                                      u'Média distância percorrida', u'ER distância percorrida',
                                                      u'Média nutrientes comidos', u'ER nutrientes comidos'])

resume = resume.set_index(u'Criaturas')
print resume.to_csv(encoding='utf-8')

#plt.errorbar(x=resume.index, y=resume['Mean lifetime'], yerr=resume['ME lifetime'])
#plt.show()
"""
fig, ax1 = plt.subplots()
sl = ax1.plot(resume.index, resume['Mean AdrenergicStimulus'], 'b--o', label=u'Estímulos trocados')
ax1.legend(loc='center left', bbox_to_anchor=(1.05, .9))
plt.setp(sl, 'linewidth', 2)
ax1.set_ylabel(u"Média estímulos adrenergicos trocados")
ax1.set_xlabel(u"Número de criaturas")


ax2 = ax1.twinx()
sl = ax2.plot(resume.index, resume['Mean lifetime'], 'r-.o', label=u'Tempo de vida')
plt.setp(sl, 'linewidth', 2)
ax2.set_ylabel(u"Tempo de vida médio")
ax2.legend(loc='center left', bbox_to_anchor=(1.05, .8))

plt.title(u"Média de estímulos adrenérgicos trocados comparado ao tempo de vida\n")
fig.tight_layout()
plt.xlim([0, 6])

plt.show()

fig.savefig('results/exp_2/adrenergic_lifetime.png', bbox_inches='tight')
"""