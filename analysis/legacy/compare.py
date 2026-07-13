
from analysis.util import *

import pandas as pd
import matplotlib.pyplot as plt

wd = '/media/felipe/0c5b2979-59fc-4555-a2d9-88e99846e8f1/'


def compare(data_name, col_name):
    baseline_1c_frames = get_data_frames(wd + 'baseline/1node_1creature', data_name)
    baseline_6c_frames = get_data_frames(wd + 'baseline/1node_6creature', data_name)
    exp1_frames = get_data_frames(wd + 'baseline_cd/1node_6creature', data_name)

    base_1c = pd.concat(baseline_1c_frames, ignore_index=True)
    base_6c = pd.concat(baseline_6c_frames, ignore_index=True)
    e1 = pd.concat(exp1_frames, ignore_index=True)

    comparision_1 = pd.DataFrame({
        u"6 criaturas": base_6c[col_name],
        u"6 criatturas densidade constante": e1[col_name]})

    comparision_2 = pd.DataFrame({
        u"1 criatura": base_1c[col_name],
        u"6 criaturas densidade constante": e1[col_name]
    })
    return comparision_1, comparision_2

lifetime_comp1, lifetime_comp2 = compare('lifetimes', 'lifetime')
lifetime_comp1 = lifetime_comp1 / 60.0
lifetime_comp2 = lifetime_comp2 / 60.0

ax = lifetime_comp1.boxplot()
fig = ax.get_figure()
fig.suptitle(u"Tempo de vida em minutos")
fig.savefig('results/lifetime_comparision1.png')
fig.show()
plt.close()

plt.figure()
ax = lifetime_comp2.boxplot()
fig = ax.get_figure()
fig.suptitle(u"Tempo de vida em minutos")
fig.savefig('results/lifetime_comparision2.png')
fig.show()
plt.close()

eaten_comp1, eaten_comp2 = compare('eatenNutrients', 'eatenNutrients')
plt.figure()
ax = eaten_comp1.boxplot()
fig = ax.get_figure()
fig.suptitle(u"Nutrientes comidos")
fig.savefig('results/eatenNutrients_comparision1.png')
plt.close()

plt.figure()
ax = eaten_comp2.boxplot()
fig = ax.get_figure()
fig.suptitle(u"Nutrientes comidos")
fig.savefig('results/eatenNutrients_comparision2.png')
plt.close()
