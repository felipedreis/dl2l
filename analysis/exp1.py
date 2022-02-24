#!/usr/bin/python
# -*- coding: UTF-8 -*-

from scipy import stats

from avgAccumulatedNutrients import *
from avgAccumulatedChoices import *
from avgExchangedStimuli import *
from avgBehaviouralEfficiency import *
from avgArousal import *

wd = '/media/felipe/0c5b2979-59fc-4555-a2d9-88e99846e8f1/'

avg_accumulated_choices(wd + 'exp_1_1/data', 'results/exp_1_l2l')
avg_accumulated_choices(wd + 'exp_1_2/data', 'results/exp_1_artifice')

avg_accumulated_nutrients(wd + 'exp_1_1/data', 'results/exp_1_l2l')
avg_accumulated_nutrients(wd + 'exp_1_2/data', 'results/exp_1_artifice')

avg_arousal(wd + 'exp_1_1/data', 'results/exp_1_l2l')
avg_arousal(wd + 'exp_1_2/data', 'results/exp_1_artifice')

avg_exchanged_stimuli(wd + 'exp_1_1/data', 'results/exp_1_l2l')
avg_exchanged_stimuli(wd + 'exp_1_2/data', 'results/exp_1_artifice')

avg_behavioural_eff(wd + 'exp_1_1/data', 'results/exp_1_l2l')
avg_behavioural_eff(wd + 'exp_1_2/data', 'results/exp_1_artifice')

exp1_lifetimes = get_data_frames(wd + 'exp_1_1/data', 'lifetimes')
exp1_eatenNutrients = get_data_frames(wd + 'exp_1_1/data', 'eatenNutrients')

exp2_lifetimes = get_data_frames(wd + 'exp_1_2/data', 'lifetimes')
exp2_eatenNutrients = get_data_frames(wd + 'exp_1_2/data', 'eatenNutrients')

exp2_lifetimes_df = pd.concat(exp2_lifetimes)
exp2_eatenNutrients_df = pd.concat(exp2_eatenNutrients)

exp1_lifetimes_df = pd.concat(exp1_lifetimes)
exp1_eatenNutrients_df = pd.concat(exp1_eatenNutrients)

del exp1_lifetimes_df['ids'], exp1_eatenNutrients_df['ids'], exp2_lifetimes_df['ids'], exp2_eatenNutrients_df['ids']

exp1_lifetimes_df = exp1_lifetimes_df / 60.0

print u"Arquitetura Artífice: "
print u"Média: s"
print exp2_lifetimes_df.mean()
print exp2_eatenNutrients_df.mean()
print "lifetime norm.: " + str(stats.shapiro(exp2_lifetimes_df))
print "eaten nutrients norm.: " + str(stats.shapiro(exp2_eatenNutrients_df))
print u"Erro: "
print exp2_lifetimes_df.apply(mean_error)
print exp2_eatenNutrients_df.apply(mean_error)

print "\n"

print u"Arquitetura DL2L: "
print u"Média: "
print exp1_lifetimes_df.mean()
print exp1_eatenNutrients_df.mean()
print "lifetime norm.: " + str(stats.shapiro(exp1_lifetimes_df))
print "eaten nutrients norm.: " + str(stats.shapiro(exp1_eatenNutrients_df))
print u"Erro: "
print exp1_lifetimes_df.apply(mean_error)
print exp1_eatenNutrients_df.apply(mean_error)
