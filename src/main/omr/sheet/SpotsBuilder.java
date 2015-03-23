//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      S p o t s B u i l d e r                                   //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Herve Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet;

import omr.Main;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.GlyphLayer;
import omr.glyph.GlyphNest;
import omr.glyph.Shape;
import omr.glyph.facets.Glyph;

import omr.image.ImageUtil;
import omr.image.MorphoProcessor;
import omr.image.StructureElement;

import omr.lag.BasicLag;
import omr.lag.JunctionRatioPolicy;
import omr.lag.Lag;
import omr.lag.Lags;
import omr.lag.Section;
import omr.lag.SectionFactory;

import omr.run.Orientation;
import omr.run.RunTable;
import omr.run.RunTableFactory;

import omr.sheet.ui.ImageView;
import omr.sheet.ui.PixelBoard;
import omr.sheet.ui.ScrollImageView;
import omr.sheet.ui.SheetTab;

import omr.ui.BoardsPane;

import omr.util.StopWatch;

import ij.process.ByteProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Class {@code SpotsBuilder} performs morphology analysis to retrieve the major spots
 * that compose beams.
 * <p>
 * It can work on a whole sheet or on a snapshot of cues aggregate.
 *
 * @author Hervé Bitteur
 */
public class SpotsBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(SpotsBuilder.class);

    /** Orientation chosen for spot runs. */
    public static final Orientation SPOT_ORIENTATION = Orientation.VERTICAL;

    //~ Instance fields ----------------------------------------------------------------------------
    /** Related sheet. */
    private final Sheet sheet;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Creates a new SpotsBuilder object.
     *
     * @param sheet the related sheet
     */
    public SpotsBuilder (Sheet sheet)
    {
        this.sheet = sheet;
    }

    //~ Methods ------------------------------------------------------------------------------------
    //-----------------//
    // buildSheetSpots //
    //-----------------//
    /**
     * Retrieve all spots from a sheet.
     * All spots are dispatched among their containing system(s).
     */
    public void buildSheetSpots ()
    {
        final StopWatch watch = new StopWatch("buildSheetSpots");

        try {
            /** Spots lag. */
            final Lag spotLag = new BasicLag(Lags.SPOT_LAG, SPOT_ORIENTATION);
            sheet.setLag(Lags.SPOT_LAG, spotLag);

            watch.start("gaussianBuffer");

            // We need a copy of image that we can overwrite.
            ByteProcessor buffer = getBuffer();

            // Retrieve major spots
            watch.start("buildSpots");

            Scale scale = sheet.getScale();
            int beam = scale.getMainBeam();
            List<Glyph> spots = buildSpots(buffer, null, beam, null);

            // Dispatch spots per system(s)
            dispatchSheetSpots(spots);

            // Display on all spot glyphs?
            if ((Main.getGui() != null) && constants.displayBeamSpots.isSet()) {
                SpotsController spotController = new SpotsController(sheet, spotLag);
                spotController.refresh();
            }
        } catch (Exception ex) {
            logger.warn("Error building spots", ex);
        } finally {
            if (constants.printWatch.isSet()) {
                watch.print();
            }
        }
    }

    //------------//
    // buildSpots //
    //------------//
    /**
     * Build spots out of the provided buffer.
     *
     * @param buffer provided buffer (it will be modified)
     * @param offset buffer offset WRT sheet coordinates, or null
     * @param beam   typical beam height
     * @param cueId  cue id for cue buffer, null for whole sheet buffer
     * @return the collection of spots retrieved
     */
    public List<Glyph> buildSpots (ByteProcessor buffer,
                                   Point offset,
                                   double beam,
                                   String cueId)
    {
        final Lag spotLag;

        // Erase Header for non-cue buffers
        if (cueId == null) {
            spotLag = sheet.getLag(Lags.SPOT_LAG);
            eraseHeaderAreas(buffer);
        } else {
            spotLag = new BasicLag(Lags.SPOT_LAG, SPOT_ORIENTATION);
        }

        final double diameter = beam * constants.beamCircleDiameterRatio.getValue();
        final float radius = (float) (diameter - 1) / 2;
        logger.debug(
                "{}Spots retrieval beam: {}, diameter: {} ...",
                sheet.getLogPrefix(),
                String.format("%.1f", beam),
                String.format("%.1f", diameter));

        final int[] seOffset = {0, 0};
        StructureElement se = new StructureElement(0, 1, radius, seOffset);
        new MorphoProcessor(se).close(buffer);

        // For visual check
        if (cueId == null) {
            BufferedImage img = null;

            // Store buffer on disk?
            if (constants.keepBeamSpots.isSet()) {
                img = buffer.getBufferedImage();
                ImageUtil.saveOnDisk(img, sheet.getId() + ".spot");
            }

            // Display the gray-level view of all spots
            if ((Main.getGui() != null) && constants.displayGraySpots.isSet()) {
                if (img == null) {
                    img = buffer.getBufferedImage();
                }

                sheet.getAssembly().addViewTab(
                        SheetTab.GRAY_SPOT_TAB,
                        new ScrollImageView(sheet, new ImageView(img)),
                        new BoardsPane(new PixelBoard(sheet)));
            }
        } else {
            if (constants.keepCueSpots.isSet()) {
                BufferedImage img = buffer.getBufferedImage();
                ImageUtil.saveOnDisk(img, sheet.getId() + "." + cueId + ".spot");
            }
        }

        // Save a specific binarized version for NOTES step
        saveNoteRuns((ByteProcessor) buffer.duplicate());

        // Binarize the spots via a global filter (no illumination problem)
        buffer.threshold(constants.beamBinarizationThreshold.getValue());

        // Runs
        RunTableFactory runFactory = new RunTableFactory(SPOT_ORIENTATION);
        RunTable spotTable = runFactory.createTable("spot", buffer);

        // Sections
        SectionFactory sectionsBuilder = new SectionFactory(spotLag, new JunctionRatioPolicy());
        List<Section> sections = sectionsBuilder.createSections(spotTable);

        if (offset != null) {
            for (Section section : sections) {
                section.translate(offset);
            }
        }

        // Glyphs
        GlyphNest nest = sheet.getNest();
        List<Glyph> glyphs = nest.retrieveGlyphs(sections, GlyphLayer.SPOT, true);

        return glyphs;
    }

    //--------------------//
    // dispatchSheetSpots //
    //--------------------//
    /**
     * Dispatch sheet spots according to their containing system(s),
     * and keeping only those within system width.
     *
     * @param spots the spots to dispatch
     */
    private void dispatchSheetSpots (List<Glyph> spots)
    {
        int count = 0;

        List<SystemInfo> relevants = new ArrayList<SystemInfo>();
        SystemManager systemManager = sheet.getSystemManager();

        for (Glyph glyph : spots) {
            Point center = glyph.getCentroid();
            systemManager.getSystemsOf(center, relevants);

            boolean created = false;

            for (SystemInfo system : relevants) {
                // Check glyph is within system abscissa boundaries
                if ((center.x >= system.getLeft()) && (center.x <= system.getRight())) {
                    glyph.setShape(Shape.BEAM_SPOT);
                    system.registerGlyph(glyph);
                    created = true;
                }
            }

            if (created) {
                count++;
            }
        }

        logger.debug("{}Spots retrieved: {}", sheet.getLogPrefix(), count);
    }

    //------------------//
    // eraseHeaderAreas //
    //------------------//
    private void eraseHeaderAreas (ByteProcessor buffer)
    {
        final int dmzDyMargin = sheet.getScale().toPixels(constants.staffVerticalMargin);

        buffer.setValue(255);

        for (SystemInfo system : sheet.getSystems()) {
            Staff firstStaff = system.getFirstStaff();
            Staff lastStaff = system.getLastStaff();
            int start = system.getBounds().x;
            int stop = firstStaff.getHeaderStop();
            int top = firstStaff.getFirstLine().yAt(stop) - dmzDyMargin;
            int bot = lastStaff.getLastLine().yAt(stop) + dmzDyMargin;

            buffer.setRoi(start, top, stop - start + 1, bot - top + 1);
            buffer.fill();
            buffer.resetRoi();
        }

        buffer.setValue(0);
    }

    //-----------//
    // getBuffer //
    //-----------//
    /**
     * Prepare the buffer to be used for beams retrieval.
     * <p>
     * Staff lines and vertical lines (especially stems) are removed because they could lead to
     * artificially larger beam candidates.
     *
     * @return the buffer to be used
     */
    private ByteProcessor getBuffer ()
    {
        final Picture picture = sheet.getPicture();
        final int stemWidth = sheet.getMaxStem();

        ///return  picture.getSource(Picture.SourceKey.GAUSSIAN);
        ByteProcessor buffer = picture.getSource(Picture.SourceKey.NO_STAFF);

        // Remove stem runs (could be much more efficient if performed on buffer directly)
        RunTableFactory factory = new RunTableFactory(
                Orientation.HORIZONTAL,
                new RunTableFactory.LengthFilter(stemWidth));
        RunTable table = factory.createTable("noStem", buffer);
        buffer = table.getBuffer();

        // Apply median filter
        buffer = picture.medianFiltered(buffer);

        // Apply gaussian filter
        return picture.gaussianFiltered(buffer);
    }

    //--------------//
    // saveNoteRuns //
    //--------------//
    /**
     * To ease (future) NOTES step, save the runs of the properly binarized buffer.
     * The result is stored into sheet instance.
     *
     * @param buffer the buffer copy to binarize
     */
    private void saveNoteRuns (ByteProcessor buffer)
    {
        // Binarize the spots with threshold for notes
        buffer.threshold(constants.noteBinarizationThreshold.getValue());

        // Runs
        RunTableFactory runFactory = new RunTableFactory(SPOT_ORIENTATION);
        RunTable runs = runFactory.createTable("noteSpots", buffer);

        // For visual check
        if (constants.keepNoteSpots.isSet()) {
            BufferedImage img = runs.getBufferedImage();
            ImageUtil.saveOnDisk(img, sheet.getId() + ".notespot");
        }

        // Save it for future NOTES step
        sheet.getPicture().setTable(Picture.TableKey.NOTE_SPOTS, runs);
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        final Constant.Boolean displayBeamSpots = new Constant.Boolean(
                false,
                "Should we display the beam Spots view?");

        final Constant.Boolean displayGraySpots = new Constant.Boolean(
                false,
                "Should we display the gray Spots view?");

        final Constant.Boolean printWatch = new Constant.Boolean(
                false,
                "Should we print out the stop watch?");

        final Constant.Boolean keepBeamSpots = new Constant.Boolean(
                false,
                "Should we store sheet beam spot images on disk?");

        final Constant.Boolean keepNoteSpots = new Constant.Boolean(
                false,
                "Should we store sheet note spot images on disk?");

        final Constant.Boolean keepCueSpots = new Constant.Boolean(
                false,
                "Should we store cue spot images on disk?");

        final Constant.Ratio beamCircleDiameterRatio = new Constant.Ratio(
                0.8,
                "Diameter of circle used to close beam spots, as ratio of beam height");

        final Constant.Integer beamBinarizationThreshold = new Constant.Integer(
                "pixel",
                140,
                "Global binarization threshold for beams");

        final Constant.Integer noteBinarizationThreshold = new Constant.Integer(
                "pixel",
                170,
                "Global binarization threshold for notes");

        final Scale.Fraction staffVerticalMargin = new Scale.Fraction(
                2.0,
                "Margin erased above & below staff header area");
    }
}
