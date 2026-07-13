#!/usr/bin/python
# -*- coding: UTF-8 -*-

import matplotlib.pyplot as plt
import cv2 as cv
import pandas as pd

from analysis.util import *

working_dir = '/media/felipe/0c5b2979-59fc-4555-a2d9-88e99846e8f1/'
graphics_dir = './results'
experiments = np.array([[1, 1], [2, 2], [2, 3]])

distances = []
lifetimes = []
eaten_nutrients = []

for experiment in experiments:
    exp_dir = working_dir + "/exp_%d_%d" % tuple(experiment)
    distance = get_data_frames(exp_dir, 'distances')
    lifetime = get_data_frames(exp_dir, 'lifetimes')
    eaten_nutrient = get_data_frames(exp_dir, 'eatenNutrients')

    distance = pd.concat(distance, ignore_index=True)
    lifetime = pd.concat(lifetime, ignore_index=True)
    eaten_nutrient = pd.concat(eaten_nutrient, ignore_index=True)
    del distance['ids'], lifetime['ids'], eaten_nutrient['ids']

    col_name = str(experiment[1]) + ' creatures'

    distance = distance.rename(columns={'distances': col_name})
    lifetime = lifetime.rename(columns={'lifetimes': col_name})
    eaten_nutrient = eaten_nutrient.rename(columns={'eatenNutrients': col_name})

    lifetime = lifetime / 60.0

    distances.append(distance)
    lifetimes.append(lifetime)
    eaten_nutrients.append(eaten_nutrient)


plt.boxplot(distances, positions=experiments[:, 1])
plt.title(u"Distancia percorrida por numero de criaturas")
plt.xlabel(u"Criaturas")
plt.ylabel(u"Distância")
plt.savefig(graphics_dir + "/boxplot_distance.png")
plt.close()

plt.boxplot(lifetimes, positions=experiments[:, 1])
plt.title(u"Tempo de vida por número de criaturas")
plt.xlabel(u"Criaturas")
plt.ylabel(u"Tempo de vida (min.)")
plt.savefig(graphics_dir + "/boxplot_lifetimes.png")
plt.close()

plt.boxplot(eaten_nutrients, positions=experiments[:, 1])
plt.title(u"Nutrientes comidos por número de criaturas")
plt.xlabel(u"Criaturas")
plt.ylabel(u"Nutrientes comidos")
plt.savefig(graphics_dir + "/boxplot_eatenNutrients.png")
plt.close()

"""
for experiment in experiments:
    tracing_img = np.zeros((600, 800, 3), dtype=np.int8)
    exp_dir = working_dir + "/%dnode_%dcreature" % tuple(experiment)
    tracing = get_data_frames(exp_dir, 'tracing')

    color = random_color()
    last_id = tracing[0, 0]
    for line in tracing:
        if line[0] != last_id:
            color = random_color()
            last_id = line[0]
        cv.line(tracing_img, (int(line[1]), int(line[2])), (int(line[3]), int(line[4])), color)

    cv.imwrite(graphics_dir + "/%dnode_%dcreature_tracing.png" % tuple(experiment), tracing_img)
"""

