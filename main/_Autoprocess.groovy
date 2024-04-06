import groovy.transform.Field
import ij.ImagePlus
import ij.gui.GUI as gui
import ij.gui.MessageDialog
import ij.gui.NonBlockingGenericDialog
import ij.IJ
import ij.WindowManager
import ij.measure.ResultsTable
import ij.plugin.ImageCalculator
import ij.plugin.filter.Analyzer
import ij.plugin.frame.RoiManager
import ij.plugin.Macro_Runner
import ij.process.LUT
import loci.plugins.BF
import loci.plugins.in.ImporterOptions

import java.awt.Frame
import java.text.DecimalFormat
import java.math.RoundingMode

//region -- CHANGE VARIABLES HERE --

//Path to folder where cellpose looks to find input files
@Field String cellposeInput = "" //Fill with path to Cellpose input folder

//Path to folder where cellpose puts its outputs
@Field String cellposeOutput = "" //Fill with path to Cellpose output folder

//Size of rolling ball when subtracting background
@Field int rollingBallSize = 150

//Width of region from which to make kymograph (pixels)
@Field int kymographWidth = 12

//Time interval of kymograph (seconds)
@Field double timeInterval = 2

//Whether kymograph should measure initiation densities
@Field boolean measureInitiations = false

//Whether program should allow user to specify which files to process, or should automatically
//process all files in a folder
@Field boolean userSelect = false

//region -- VARIABLES (DO NOT CHANGE) --

@Field String directory = "";

@Field ArrayList<String> cellposeProcessList = new ArrayList<>();

@Field ArrayList<String> otherChannelsList = new ArrayList<>();

// region -- MAIN --
fileSelect()

//region -- USER DEFINED FUNCTIONS --

/**
* Prompts the user to select a folder which contains all movies/images that should be processed with this
* workflow. If an image file exists directly in the selected folder, the image will automatically be processed.
* Otherwise, the user will be asked to select which movie in the subfolder to process, and given the option to view
* the image(s) before making a choice. Selectchannels() is called on each movie needing processsing. Calls processCellpose()
* once every image in the directory is iterated through.
*
* @return aborts if directory input is malformed
*/
void fileSelect() {
    //Deletes files currently in the Cellpose input folder from previous runs
    File cellposeInputFile = new File(cellposeInput)
    for (File f: cellposeInputFile.listFiles(new ImageTypeFilter())) {
        f.delete()
    }

    //Prompts user to select directory
    def fileSelect = gui.newNonBlockingDialog("Select Folder/Files")
    fileSelect.addDirectoryField("Select folder to process", "~", 25);
    fileSelect.addCheckbox("Open images before image selection", false);
    fileSelect.showDialog()
    directory = fileSelect.getNextString()
    boolean openImages = fileSelect.getNextBoolean()

    //Errors directory selection if invalid
    File directoryFile = new File(directory);
    if (!directoryFile.isDirectory()) {
        error = new MessageDialog(new Frame(), "Error", "Selected directory is not a valid directory.");
        return; //aborts
    }

    //Iterate over everything in the given folder
    File[] directoryFileList = directoryFile.listFiles(new ImageTypeFilter()) //Will reject non-image files
    for (String name: ImageTypeFilter.rejectedFiles) {
        print("Skipped " + name + " because it is not an image or folder")
    }
    for (File f: directoryFileList) {
        //if file is outside of a folder
        if (f.isFile()) {
            //immediately open image & get correct channel to process
            selectChannels(f)
        } else if (f.isDirectory()) {//if is subdirectory of selected directory
            File[] subdirectoryFileList = f.listFiles(new ImageTypeFilter());

            def imageSelect = null
            if (userSelect) {
                //skip if folder is empty
                if (subdirectoryFileList.length == 0) {
                    print("Skipped folder " + f.getName() + " because it is empty or does not contain any images")
                    continue;
                }

                //open and show images
                ArrayList<ImagePlus> openImagePlus = new ArrayList<>();
                if (openImages) {
                    for (File subFile : subdirectoryFileList) {
                        ImagePlus openSubFile = IJ.open(subFile.getAbsolutePath())
                        openSubFile.show()
                        openImagePlus.add(openSubFile)
                    }
                }

                //prompt user to select image(s)
                imageSelect = new NonBlockingGenericDialog("Select Image(s) in Folder " + f.getName())
                for (File subFile : subdirectoryFileList) {
                    imageSelect.addCheckbox(subFile.getName(), false)
                }
                imageSelect.showDialog()
                for (ImagePlus openImage : openImagePlus) {
                    openImage.close()
                }
            }

            //selects channels for specified image
            for (File subFile: subdirectoryFileList) {
                if (!userSelect || (imageSelect != null && imageSelect.getNextBoolean())) {
                    selectChannels(subFile)
                }
            }
        }

        //closes open image windows before moving on to the next image needing processing
        closeImageWindows()
    }

    //starts processing images through Cellpose once file selection finishes
    processCellpose()
}

/**
* Given an image file, separates the image file into separate images for each color channel and saves
* the single-channel image as a new TIF file, if it does not already exist. The program attempts to locate
* the index of the GFP to pass into processing by Cellpose (since the GFP channel tends to have the best signal
* compared to fluorophores of other colors), and if a GFP channel is not found, will use the earliest-indexed channel.
* All other channels in the image are queued for processing, skipping the Cellpose step.
*
* @param imageFile the File object to separate channels from
*/
void selectChannels(File imageFile) {
    //Opens imageFile
    ImporterOptions options = new ImporterOptions()
    options.setId(imageFile.getAbsolutePath())
    options.setSplitChannels(true)
    ImagePlus[] imageChannels = BF.openImagePlus(options)

    //Finds the index of the green/GFP channel
    int selectedChannel
    for (ImagePlus channel: imageChannels) {
        LUT[] luts = channel.getLuts()
        if (luts[0].toString().contains("green")) {
            selectedChannel = new ArrayList<ImagePlus>(Arrays.asList(imageChannels)).indexOf(channel)
        }
    }

    //Iterates through all channels in image
    for (ImagePlus channel: imageChannels) {
        //Saves channel as a separate TIF image (if not already existing)
        channel.show()
        File newChannel = new File(imageFile.getParent() + File.separator + channel.getTitle())
        String toAdd = newChannel.getAbsolutePath()
        if (!newChannel.exists()) {
            IJ.saveAs(channel, ".tif",  newChannel.getAbsolutePath() + ".tif")
            toAdd += ".tif"
        }

        //Either queues the channel for processing by Cellpose first, or skips
        if (Integer.valueOf(channel.getTitle().substring(channel.getTitle().length() - 5, channel.getTitle().length() - 4)) == selectedChannel) {
            cellposeProcessList.add(toAdd)
        } else {
            otherChannelsList.add(toAdd)
        }
    }
}

/**
* For each image that has been queued for processing by Cellpose
*    1. Creates directory to store processed results from this image titled "<name> Processed Results"
*    2. Calls sbcb() to subtract background and correct photobleaching
*    3. Processes each file to improve results from Cellpose processing
*         a. Sum Z Projection of corrected image after Step 2
*         b. Median filter
*         c. Erosion filter
*    4. Saves each file to the Cellpose input directory
* After these steps, calls runCellpose() to execute Cellpose
*/
void processCellpose() {
    //Iterates through all files to process
    for (String imagePath: cellposeProcessList) {
        //Create directory to save all processed image files
        File image = new File(imagePath)
        String name = image.getName().substring(0, image.getName().size()-4)
        File processedResults = new File(image.getParent() + File.separator + name + " Processed Results")
        processedResults.mkdir()

        //Opens image
        IJ.open(imagePath)
        ImagePlus original = IJ.getImage()

        //Calls sbcb() on each image
        sbcb(original, name, processedResults)

        //Processes files for cellpose
        IJ.open(processedResults.getAbsolutePath() + File.separator + name + "_SBCB.tif")

        IJ.run("Z Project...", "projection=[Sum Slices]") // Sum Z Projection
        IJ.run("Median...", "radius=5") // Median filter

        IJ.run("Morphological Filters", "operation=Erosion element=Disk radius=4") //Erosion

        //Saves processed files
        IJ.saveAs("tif", processedResults.getAbsolutePath() + File.separator + name + "_SBCBsumZradius5erosion4.tif")
        IJ.saveAs("tif", cellposeInput + name + "_SBCBsumZradius5erosion4.tif")

        //Closes windows
        closeImageWindows()
        IJ.selectWindow("Results")
        IJ.run("Close")
    }

    runCellpose()
}

/**
* Processes the given image by (new image is generated and saved at each step):
*    1. Subtracting background signal, rolling ball size can be set in variable at start of script
*    2. Corrects for photobleaching by multiplying the values of each timestep by the first timestep
*       signal : current timestep signal ratio
*    3. Subtracts original image from median filtered image to lessen background signal
*    4. Max Intensity Z Projection
*
* @param image  Image to process by subtracting background and correct photobleaching
* @param name   Name of image
* @param processed Results   Folder to save processed image(s) to
*/
void sbcb(ImagePlus image, String name, File processedResults) {
    //Subtract Background
    IJ.run("Subtract Background...", "rolling = " + rollingBallSize.toString() + " stack")
    IJ.saveAs(image, ".tif", processedResults.getAbsolutePath() + File.separator + name + "_SB.tif")

    //Correct photobleaching
    if (image.getNSlices() == 0) {
        IJ.run("Convert Images to Stack")

    }
    IJ.run("Select All")
    IJ.run("Set Slice...", "slice=" + 1);
    IJ.run("Set Measurements...", "  mean redirect=None decimal=9")
    IJ.run("Select None")

    double picsum1
    for (int i = 0; i < image.getNFrames() + 1; i++) {
        IJ.run("Restore Selection")
        IJ.run("Clear Results")
        IJ.run("Measure")

        ResultsTable results = Analyzer.getResultsTable()
        double picsum = results.getValue("Mean", 0)
        if (i == 0) {
            picsum1 = picsum;
        }

        double ratio = picsum1 / picsum
        IJ.run("Select None");
        IJ.run("Multiply...", "slice value=" + ratio)
        IJ.run("Next Slice [>]")
    }
    IJ.saveAs(image, ".tif", processedResults.getAbsolutePath() + File.separator + name + "_SBCB.tif")

    //median
    File kymoDir = new File(processedResults.getParent() + File.separator + name.substring(0, name.length()-5) + " Kymographs")
    if (!kymoDir.exists()) {
        kymoDir.mkdir()
    }
    IJ.open(processedResults.getAbsolutePath() + File.separator + name + "_SBCB.tif")
    originalImg = WindowManager.getCurrentImage()
    IJ.run("Duplicate...", "duplicate")
    IJ.run("Median...", "radius=10 stack")

    //subtract
    newImg = WindowManager.getCurrentImage()
    ImagePlus subtractedImg = ImageCalculator.run(originalImg, newImg, "subtract create stack")
    subtractedImg.show()
    IJ.run("Enhance Contrast", "saturated=0.35")
    IJ.saveAs("tif", kymoDir.getAbsolutePath() + File.separator + name + "_subMedian10.tif")

    //Z Project
    IJ.selectWindow(name + "_SBCB.tif")
    IJ.run("Z Project...", "projection=[Max Intensity]")
    IJ.run("Enhance Contrast", "saturated=0.35")
    IJ.saveAs("tif", processedResults.getAbsolutePath() + File.separator + name + "_SBCBMAX.tif")

    //close
    closeImageWindows()
}

/**
* Calls Bash script which runs Cellpose on the images stored in the cellpose input folder from 
* the processCellpose() function. The Cellpose Python program is not directly called because of 
* compatibility problems with ImageJ and the potential need to activate a separate environment.
*/
void runCellpose() {
    //delete files output folder before running
    File cellposeOutputFile = new File(cellposeOutput)
    for (File f: cellposeOutputFile.listFiles(new ImageTypeFilter())) {
        f.delete()
    }

    //calls bash script to run cellpose
    Macro_Runner cellpose = new Macro_Runner()
    cellpose.runMacro("_Cellpose", null)
    processOtherChannels()
    //wait until cellpose finishes
    while (cellposeOutputFile.listFiles(new ImageTypeFilter()).size() == 0) {
        wait(1000)
    }
    afterCellpose()
}

/**
* For the channels of the images which are not processed through Cellpose, still perform background subtraction
* and photobleach correction, and save the processed images, by caling sbcb().
*/
void processOtherChannels() {
    for (String imagePath: otherChannelsList) {
        //Create directory to save all processed image files
        File image = new File(imagePath)
        String name = image.getName().substring(0, image.getName().length() - 4)
        File processedResults = new File(image.getParent() + File.separator + name + " Processed Results")
        processedResults.mkdir()

        IJ.open(imagePath)
        original = IJ.getImage()

        //SBCB etc
        sbcb(original, name, processedResults)
    }
}

/**
 * Function which takes each of the cellpose masks, creates line ROIs from them, then creates color
 * composite kymographs for each line ROI.
 */
void afterCellpose() {
    File cellposeOutputFile = new File(cellposeOutput)
    if (cellposeOutputFile.listFiles(new ImageTypeFilter()).size() == 0) {
        print("Cellpose did not run")
        return
    }
    File processedResults
    String name
    for (File f: cellposeOutputFile.listFiles(new ImageTypeFilter())) {
        //save in correct folder
        ImagePlus cellposeMask = BF.openImagePlus(f.getAbsolutePath())[0]
        boolean found = false
        for (String imagePath: cellposeProcessList) {
            File parent = new File(imagePath)
            name = parent.getName().substring(0, parent.getName().length() - 4)
            if ((f.getName().substring(0, f.getName().length()-33)).equals(name)) {
                found = true
                processedResults = new File(parent.getParent() + File.separator + name + " Processed Results")
                if (!processedResults.exists()) {
                    processedResults.mkdir()
                    print("Error: Could not find where " + f.getName() + " is originally located")
                }
                cellposeMask.show()
                IJ.saveAs(cellposeMask, "tif", processedResults.getAbsolutePath() + File.separator + f.getName())
                break;
            }
        }
        if (!found) {
            print("Could not find matching image path for " + f.getName().substring(0, f.getName().length()-33) + ".tif")
            break;
        }

        //Fill holes - disable this if the following programs are not installed
        IJ.run("Fill Holes (Binary/Gray)")
        IJ.run("glasbey")
        IJ.saveAs("tif", processedResults.getAbsolutePath() + File.separator + f.getName() + "_filledHoles.tif")

        //get ROIs
        IJ.run("LabelImageToRoiManager 5")
        IJ.run("MoveROIBreaks 1")
        RoiManager roimanager = RoiManager.getRoiManager()
        roimanager.save(processedResults.getAbsolutePath() + File.separator + f.getName() + " ROIs.zip")

        //create kymographs
        closeImageWindows()
        File kymoDir = new File(processedResults.getParent() + File.separator + name.substring(0, name.length()-5) + " Kymographs")
        if (!kymoDir.exists()) {
            print("Could not find kymograph directory " + kymoDir.getName())
        }
        createColorKymographs(kymoDir, roimanager)

        closeImageWindows()
    }

    success = new MessageDialog(new Frame(), "Process finished", "All " + cellposeProcessList.size() + " images have been processed and saved.")
}

/**
 * Function which creates composite color kymographs, assuming line ROIs are stored in the ROI Manager.
 *
 * @param kymoDir: Directory where created kymographs should be stored
 * @param roimanager: ROI Manager where line ROIs are stored
 */
void createColorKymographs(File kymoDir, RoiManager roimanager) {
    File pngKymoDir = new File(kymoDir.getAbsolutePath() + File.separator + "PNGs")
    pngKymoDir.mkdir()

    //Find all channel colors
    ArrayList<ImagePlus> channelImages = new ArrayList<>();
    for (File channelMedian: kymoDir.listFiles(new ImageTypeFilter())) {
        if (channelMedian.getName().contains("_subMedian10")) {
            IJ.open(channelMedian.getAbsolutePath())
            ImagePlus channel = IJ.getImage()
            channelImages.add(channel)
            channel.hide()
        }
    }
    channelImages.sort(new channelComparator())

    //Iterate through ROIs
    for (int i = 0; i < roimanager.getCount(); i++) {
        //create kymographs of each color
        ArrayList<String> colors = new ArrayList<>()
        for (ImagePlus channel: channelImages) {
            channel.show()
            LUT[] luts = channel.getLuts()
            if (luts[0].toString().contains("green") || luts[0].toString().contains("#38ff00") || luts[0].toString().contains("#8dff00")) {
                colors.add("Green")
            } else if (luts[0].toString().contains("blue") || luts[0].toString().contains("#0066ff")) {
                colors.add("Blue")
            } else if (luts[0].toString().contains("red")) {
                colors.add("Red")
            } else {
                print("Unknown color: " + luts[0].toString())
                return
            }

            roimanager.select(channel, i)
            IJ.run("Straighten...", "line=" + kymographWidth + " process")
            ImagePlus tempImg1 = IJ.getImage()
            IJ.run("Reslice [/]...", "output=1.000 start=Top avoid")
            ImagePlus tempImg2 = IJ.getImage()
            IJ.run("Z Project...", "projection=[Max Intensity]")
            IJ.run("Rotate 90 Degrees Left")
            tempImg1.close()
            tempImg2.close()
            channel.hide()
        }

        //stack images into composite
        if (channelImages.size() == 2) {
            IJ.run("Images to Stack", "name=StackTwoColor use")
            IJ.run("Make Composite", "display=Composite")
            IJ.setSlice(1)
            IJ.run(colors.get(0))
            IJ.setSlice(2)
            IJ.run(colors.get(1))
        } else if (channelImages.size() == 3) {
            IJ.run("Images to Stack", "name=StackThreeColor use")
            IJ.run("Make Composite", "display=Composite")
            IJ.setSlice(1)
            IJ.run(colors.get(0))
            IJ.setSlice(2)
            IJ.run(colors.get(1))
            IJ.setSlice(3)
            IJ.run(colors.get(2))
        } else if (channelImages.size() == 1) {
            IJ.run(colors.get(0))
        } else {
            print("Image has " + channelImages.size() + " colors, not 1, 2, or 3")
            return
        }
        IJ.saveAs("TIF", kymoDir.getAbsolutePath() + File.separator + (i + 1).toString() + "_composite_kymograph")
        IJ.saveAs("PNG", pngKymoDir.getAbsolutePath() + File.separator + (i + 1).toString() + "_composite_kymograph")
        closeImageWindows()
    }
}

//Utility function which closes open windows
void closeImageWindows() {
    for (String imageTitle: WindowManager.getImageTitles()) {
        IJ.selectWindow(imageTitle)
        IJ.run("Close")
    }
}

//region --UTILITY CLASSES--

class ImageTypeFilter implements FileFilter {

    //change accepted image file formats here
    ArrayList<String> fileTypes = new ArrayList<>(Arrays.asList("tif", "tiff", "nd2"));

    static ArrayList<String> rejectedFiles = new ArrayList<>();

    /**
     * Only accepts files that are images or folders
     */
    @Override
    boolean accept(File f) {
        String pathname = f.getAbsolutePath()

        if (f.isDirectory()) {
            return true;
        }

        for (String fileType: fileTypes) {
            if (pathname.contains(fileType)) {
                if (!pathname.contains("DIC")) {
                    return true;
                }
            }
        }

        rejectedFiles.add(f.getName());
        return false;
    }
}

class channelComparator implements Comparator {

    @Override
    int compare(Object o1, Object o2) {
        o1 = (ImagePlus) o1
        int channel1 = Integer.valueOf(o1.getShortTitle().substring(o1.getShortTitle().length() - 13, o1.getShortTitle().length() - 12))
        o2 = (ImagePlus) o2
        int channel2 = Integer.valueOf(o2.getShortTitle().substring(o2.getShortTitle().length() - 13, o2.getShortTitle().length() - 12))
        return channel1 - channel2
    }
}
