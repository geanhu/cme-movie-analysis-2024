# cme-movie-analysis-2024

This repository contains code used for image analysis in [Sun et al. 2024, PLOS Biology](https://doi.org/10.1371/journal.pbio.3002833). For details and usage examples, please consult the above publication, specifically Figure 1 and the "Image and data analysis" subsection of the Methods section.

## Installation
1. Users will need a working installation of the Fiji distribution of ImageJ2, installable [here](https://imagej.net/software/fiji/).
    1. The following program was tested on a custom Fiji installation, so additional plugins may need to be installed for functionality
2. Install the Cellpose Python package, as described in [Stringer et al. 2021](https://doi.org/10.1038/s41592-020-01018-x). Installation in a separate Conda environment is highly recommended, with installation instructions [here](https://github.com/mouseland/cellpose), and Jupyter Notebook.
3. Clone or download all files in 'main' (can be located anywhere) and 'locate-in-imagej' (must be located inside local ImageJ installation)
4. Edit files with the correct file paths
    1. Change the cellposeInput and cellposeOutput fields in `_Autoprocess.groovy` to folders that will store the input and output of Cellpose processing
    2. Change the path in `_Cellpose.ijm` to the location of `runCellpose.bash` is located
    3. Change the path in `runCellpose.bash` to the location of `run_cellpose_yeast_v0.2.ipynb`
    4. In `runCellpose.bash`, update `bash`, `jupyter`, and `conda` to point to the absolute location of each command (find by typing `which <package name, e.g. jupyter>` in terminal)
    5. In a terminal window, run `chmod +X runCellpose.bash` to make sure the bash file is executable

## Usage
1. Open script in Fiji and click "run"
2. User will be prompted to select a folder containing all images to process at in one run (please consult comments in `_Autoprocess.groovy` for folder formatting and execution specifics)
3. Program will run automatically, ONLY IF screen display is on (may need to adjust system settings to prevent sleep/screensaver)

## Attributions
- `run_cellpose_yeast_v0.2.ipynb` was adapted by Jonathan Kuo and Johannes Schoeneberg from the [Cellpose github](https://github.com/mouseland/cellpose)
- `LabelImageToAreaRoiManager_1.groovy` was adapted by Jonathan Kuo from [script](https://gist.github.com/NicoKiaru/ae00117cd6d33fea500d2867a5e669d9) written by Nicolas Chiaruttini
- The color kymograph generation portion of `_Autoprocess.groovy` was adapted by Gean Hu from software developed by [Akamatsu et al. 2020](https://doi.org/10.7554/eLife.49840) (code located in [this repo](https://github.com/DrubinBarnes/Akamatsu_CME_manuscript))
- Other files were written by Gean Hu, unless otherwise indicated

## License
BSD 3-clause License
Copyright (c) 2024, the respective contributors, as outlined above.
