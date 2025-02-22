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
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.awt.Frame

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
* Selectchannels() is called on each movie needing processsing. Calls processCellpose()
* once every image in the directory is iterated through.
*
* @return aborts if directory input is malformed
*/
void fileSelect() {
    //deletes files in cellpose input to prevent re-processing
    File cellposeInputFile = new File(cellposeInput)
    for (File f: cellposeInputFile.listFiles(new ImageTypeFilter())) {
        f.delete()
    }

    //User selects directory
    def fileSelect = gui.newNonBlockingDialog("Select Folder/Files")
    fileSelect.addDirectoryField("Select folder to process", "~", 25);
    fileSelect.showDialog()
    directory = fileSelect.getNextString()

    //Errors directory selection if invalid
    File directoryFile = new File(directory);
    if (!directoryFile.isDirectory()) {
        error = new MessageDialog(new Frame(), "Error", "Selected directory is not a valid directory.");
        return //aborts
    }

    //Iterate over everything in the given folder
    File[] directoryFileList = directoryFile.listFiles(new ImageTypeFilter()) //Will reject non-image files
    for (String name: ImageTypeFilter.rejectedFiles) { //Print rejected files for debugging
        println("Skipped " + name + " because it is not an image or folder")
    }
    for (File f: directoryFileList) {
        //if file is outside of a folder
        if (f.isFile()) {
            //immediately open image & get correct channel to process
            selectChannels(f)
        } else if (f.isDirectory()) {//if is subdirectory of selected directory
            File[] subdirectoryFileList = f.listFiles(new ImageTypeFilter());

            //selects channels for specified image
            for (File subFile: subdirectoryFileList) {
                selectChannels(subFile)
            }
        }

        closeImageWindows()
    }
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
//Separate channels into use for Cellpose & not
void selectChannels(File imageFile) {
    //Open image
    ImporterOptions options = new ImporterOptions()
    options.setId(imageFile.getAbsolutePath())
    options.setSplitChannels(true)
    ImagePlus[] imageChannels = BF.openImagePlus(options)

    //Use green channel, else use first channel
    ImagePlus selectedChannel = imageChannels[0]
    for (ImagePlus channel: imageChannels) {
        if (findColor(channel) == "Green") {
            selectedChannel = channel
        }
    }

    //Save each channel as separate image
    for (ImagePlus channel: imageChannels) {
        if (findColor(channel) != "Gray" && findColor(channel) != "") { //Skip DIC image
            channel.show()
            File newChannel = new File(imageFile.getParent() + File.separator + channel.getTitle() + ".tif")
            String toAdd = newChannel.getAbsolutePath()

            //If is Z-stack, flatten first
            if (channel.getNFrames() > 1 && channel.getNSlices() > 1) {
                IJ.run("Z Project...", "projection=[Max Intensity] all")
                if (selectedChannel == channel) {
                    selectedChannel = IJ.getImage()
                }
                channel = IJ.getImage()
            }

            if (!newChannel.exists()) {
                IJ.saveAs(channel, ".tif",  newChannel.getAbsolutePath())
            }

            if (selectedChannel == channel) {
                cellposeProcessList.add(toAdd)
            } else {
                otherChannelsList.add(toAdd)
            }
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
        String name = image.getName().substring(0, image.getName().lastIndexOf('.')) //Name without file ext
        File processedResults = new File(image.getParent() + File.separator + name + " Processed Results")
        if (!processedResults.exists()) {
            processedResults.mkdir()
        }

        //Open image to process
        IJ.open(imagePath)
        ImagePlus original = IJ.getImage()

        //SBCB etc
        sbcb(original, name, processedResults)

        //Processes files for cellpose
        IJ.open(processedResults.getAbsolutePath() + File.separator + name + "_SBCB.tif")

        IJ.run("Z Project...", "projection=[Sum Slices]")
        IJ.run("Median...", "radius=5")

        IJ.run("Morphological Filters", "operation=Erosion element=Disk radius=4")
        IJ.wait(10000)

        IJ.saveAs("tif", processedResults.getAbsolutePath() + File.separator + name + "_SBCBsumZradius5erosion4.tif")
        IJ.saveAs("tif", cellposeInput + name + "_SBCBsumZradius5erosion4.tif")

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
    String basename = name
    if (name.contains(" - C=")) {
        basename = name.substring(0, name.lastIndexOf(" - C="))
    }
    File kymoDir = new File(processedResults.getParent() + File.separator + basename + " Kymographs")
    if (!kymoDir.exists()) {
        kymoDir.mkdir()
    }
    IJ.open(processedResults.getAbsolutePath() + File.separator + name + "_SBCB.tif")
    originalImg = WindowManager.getCurrentImage()
    IJ.run("Duplicate...", "duplicate")
    IJ.run("Median...", "radius=10 stack")

    newImg = WindowManager.getCurrentImage()
    ImagePlus subtractedImg = ImageCalculator.run(originalImg, newImg, "subtract create stack")
    subtractedImg.show()
    IJ.run("Enhance Contrast", "saturated=0.35")
    // IJ.run("Grays")
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

    //calls bash script to run cellpose / does not call jupyter directly because need to activate environment first
    Macro_Runner cellpose = new Macro_Runner()
    cellpose.runMacro("_Cellpose", null)
    //Process other channels while Cellpose running
    processOtherChannels()
    //wait until cellpose finishes
    while (cellposeOutputFile.listFiles(new ImageTypeFilter()).size() == 0) {
        IJ.wait(5000)
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
        String name = image.getName().substring(0, image.getName().lastIndexOf('.'))
        File processedResults = new File(image.getParent() + File.separator + name + " Processed Results")
        if (!processedResults.exists()) {
            processedResults.mkdir()
        }

        IJ.open(imagePath)
        ImagePlus original = IJ.getImage()

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

    //Check for Cellpose error
    if (cellposeOutputFile.listFiles(new ImageTypeFilter()).size() == 0) {
        println("Cellpose did not run")
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
            name = parent.getName().substring(0, parent.getName().lastIndexOf('.'))
            String fileName = f.getName().substring(0, f.getName().lastIndexOf('_SBCB'))

            if (fileName == name) {
                found = true
                processedResults = new File(parent.getParent() + File.separator + name + " Processed Results")
                if (!processedResults.exists()) {
                    processedResults.mkdir()
                    println("Error: Could not find where " + f.getName() + " is originally located")
                    return
                }
                cellposeMask.show()
                IJ.saveAs(cellposeMask, "tif", processedResults.getAbsolutePath() + File.separator + f.getName())
                break;
            }
        }
        if (!found) {
            println("Could not find matching image path for " + f.getName().substring(0, f.getName().lastIndexOf('_SBCB')) + ".tif")
            return
        }

        //fill holes
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
        String basename = name
        if (name.contains(" - C=")) {
            basename = name.substring(0, name.lastIndexOf(" - C="))
        }
        File kymoDir = new File(processedResults.getParent() + File.separator + basename + " Kymographs")
        if (!kymoDir.exists()) {
            println("Could not find kymograph directory " + kymoDir.getName())
            break
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
    println(kymoDir.getName() + " has " + channelImages.size() + " colors")
    channelImages.sort(new channelComparator())

    //Iterate through ROIs
    for (int i = 0; i < roimanager.getCount(); i++) {
        //create kymographs of each color
        ArrayList<String> colors = new ArrayList<>()
        ArrayList<ImagePlus> channelsToRemove = new ArrayList<>()
        for (ImagePlus channel: channelImages) {
            channel.show()
            if (findColor(channel) == "Green") {
                colors.add("Green")
            } else if (findColor(channel) == "Blue") {
                colors.add("Blue")
            } else if (findColor(channel) == "Red") {
                colors.add("Red")
            } else {
                channelsToRemove.add(channel)
                continue
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
        for (ImagePlus channel: channelsToRemove) {
            channelImages.remove(channel)
            println("Not generating kymograph for " + channel.getShortTitle())
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
            println("Image has " + channelImages.size() + " colors, not 1, 2, or 3")
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

//Find color from open image
String findColor(ImagePlus image) {
    LUT luts = image.getLuts()[0]
    String lutString = luts.toString()

    String red = "#ff[0-9A-F]{4}"
    Pattern redPattern = Pattern.compile(red, Pattern.CASE_INSENSITIVE)
    Matcher redMatcher = redPattern.matcher(lutString)

    String green = "#[0-9A-F]{2}ff[0-9A-F]{2}"
    Pattern greenPattern = Pattern.compile(green, Pattern.CASE_INSENSITIVE)
    Matcher greenMatcher = greenPattern.matcher(lutString)

    String blue = "#[0-9A-F]{4}ff"
    Pattern bluePattern = Pattern.compile(blue, Pattern.CASE_INSENSITIVE)
    Matcher blueMatcher = bluePattern.matcher(lutString)

    if (lutString.contains("green") || greenMatcher.find()) {
        return "Green"
    } else if (lutString.contains("blue") || blueMatcher.find()) {
        return "Blue"
    } else if (lutString.contains("red") || redMatcher.find()) {
        return "Red"
    } else if (lutString.contains("white")) {
        return "Gray"
    } else {
        println("Unknown color: " + lutString)
        return ""
    }
}

//region --UTILITY CLASSES--

class ImageTypeFilter implements FileFilter {

    //change accepted image file formats here
    ArrayList<String> fileTypes = new ArrayList<>(Arrays.asList("tif", "tiff", "nd2", "czi"));

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
                if (!pathname.contains("DIC") && !f.getName().startsWith(".")) { //reject DIC images
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
        int channel1index = o1.getShortTitle().lastIndexOf("=")
        int channel1 = Integer.valueOf(o1.getShortTitle().substring(channel1index + 1, channel1index + 2))

        o2 = (ImagePlus) o2
        int channel2index = o2.getShortTitle().lastIndexOf("=")
        int channel2 = Integer.valueOf(o2.getShortTitle().substring(channel2index + 1, channel2index + 2))

        return channel1 - channel2
    }
}
