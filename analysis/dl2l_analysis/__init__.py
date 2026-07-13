"""Shared scaffold for DL2L experiment analysis scripts.

Extracted from the ~120-line duplicated block found in
analysis/exp_rotten_fruit_v1.py and analysis/exp_20260709_memory_vs_wm_v1.py:
parquet loading across conditions x trials, born_time/tick-rank enrichment,
Kruskal-Wallis + Bonferroni stats, and report/figure conventions.
"""
