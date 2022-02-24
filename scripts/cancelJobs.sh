#!/usr/bin/env bash

for i in `squeue2 | cut -d" " -f1 | grep -o "[0-9]*"`;
    do scancel $i;
done