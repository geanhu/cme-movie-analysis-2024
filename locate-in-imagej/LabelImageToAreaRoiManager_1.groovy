#@ImagePlus imp

import ij.ImagePlus;
import ij.gui.Roi;
import java.util.HashSet;
import ij.process.ImageProcessor;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.RoiManager;
import ij.process.FloatProcessor
import ij.*


rois = labelImageToRoiArray(imp)
putRoisToRoiManager(rois,false);


	//------------- HELPERS

	public ArrayList<Roi> labelImageToRoiArray(ImagePlus imp) {
		ArrayList<Roi> roiArray = new ArrayList<>();
		ImageProcessor ip = imp.getProcessor();
		float[][] pixels = ip.getFloatArray();
		
		HashSet<Float> existingPixelValues = new HashSet<>();

		for (int x=0;x<ip.getWidth();x++) {
			for (int y=0;y<ip.getHeight();y++) {
				existingPixelValues.add((pixels[x][y]));
			}
		}

		// Converts data in case thats a RGB Image	
		fp = new FloatProcessor(ip.getWidth(), ip.getHeight())	
		fp.setFloatArray(pixels)
		imgFloatCopy = new ImagePlus("FloatLabel",fp)

		existingPixelValues.each { v ->
			fp.setThreshold( v,v,ImageProcessor.NO_LUT_UPDATE);
			Roi roi = ThresholdToSelection.run(imgFloatCopy);
			roi.setName(Integer.toString((int) (double) v));
			roiArray.add(roi);
		}
		return roiArray;
	}

    public static void putRoisToRoiManager(ArrayList<Roi> rois, boolean keepROISName) {
        RoiManager roiManager = RoiManager.getRoiManager();
        if (roiManager==null) {
            roiManager = new RoiManager();
        }
		    roiManager.reset();
        for (int i = 0; i < rois.size(); i++) {
        	if (!keepROISName) {
        		rois.get(i).setName("a-"+i);
        	}
            roiManager.addRoi(rois.get(i));
        }
    }

	public void AreaRoisToLineRois(ImagePlus imp) {
		RoiManager rm = RoiManager.getRoiManager();
		ArrayList<Integer> deleteArray = new ArrayList<Integer>();

		// Identify composite ROIs and add their positions to an array
		// Must use several for loops because deleting ROI mutates number of rois
		for (int i = 0; i < rois.size(); i++) {
        	rm.select(i);
        	if (imp.getRoi().getTypeAsString() == "Composite"){
        		println(imp.getRoi().getName());
        		deleteArray.add(i);
        	}
		}
		
		// Delete composite ROIs
		nROIS = rm.("count");
		for (int j = nROIS - 1; j >= 0; j--) {
			rm.select(j)
			if (deleteArray.contains(Integer.parseInt(imp.getRoi().getName()))) {
				rm.runCommand(imp, "Delete");
			}
		}

		// Area to Line
		nROIS = rm.("count");
		for (int i = 0; i < nROIS; i++) {
        	rm.select(i);
        	name = imp.getRoi().getName()
        	IJ.run(imp, "Area to Line", "");
        	rm.addRoi(imp.getRoi().setName(""+100+name));
        }

		// Delete old ROIs
        for (int i = nROIS - 1; i >= 0; i--) {
        	rm.select(i);
        	rm.runCommand(imp, "Delete");
        }
	}
