#!/bin/bash

#Additional setup:
# - set bash file executable with chmod +x <file name>

eval "$(conda shell.bash hook)"
conda activate cellpose #change if your environment name is not cellpose
echo "Starting Cellpose!"
cd "path-to-cellpose-ipynb"
jupyter nbconvert --execute --to notebook --allow-errors --inplace run_cellpose_yeast_v0.2.ipynb
echo "Done!"
