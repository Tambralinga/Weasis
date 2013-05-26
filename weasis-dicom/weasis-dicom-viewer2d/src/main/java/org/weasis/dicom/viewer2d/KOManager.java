package org.weasis.dicom.viewer2d;

import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.FilteredImageSource;
import java.awt.image.ImageFilter;
import java.awt.image.ImageProducer;
import java.awt.image.RGBImageFilter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;

import org.dcm4che2.data.DicomObject;
import org.weasis.core.api.gui.util.ActionState;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.JMVUtils;
import org.weasis.core.api.gui.util.RadioMenuItem;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.editor.image.ShowPopup;
import org.weasis.core.ui.editor.image.ViewButton;
import org.weasis.core.ui.util.TitleMenuItem;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.KOSpecialElement;
import org.weasis.dicom.codec.utils.DicomMediaUtils;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.LoadDicomObjects;

public class KOManager {

    public static final ImageIcon KO_STAR_ICON = new ImageIcon(View2d.class.getResource("/icon/16x16/star_bw.png"));
    public static final ImageIcon KO_STAR_ICON_SELECTED;
    public static final ImageIcon KO_STAR_ICON_EXIST;

    static {
        ImageFilter imageFilter = new SelectedImageFilter(new float[] { 1.0f, 0.78f, 0.0f }); // ORANGE
        ImageProducer imageProducer = new FilteredImageSource(KO_STAR_ICON.getImage().getSource(), imageFilter);
        KO_STAR_ICON_SELECTED = new ImageIcon(Toolkit.getDefaultToolkit().createImage(imageProducer));

        imageFilter = new SelectedImageFilter(new float[] { 0.0f, 0.39f, 1.0f }); // BLUE
        imageProducer = new FilteredImageSource(KO_STAR_ICON.getImage().getSource(), imageFilter);
        KO_STAR_ICON_EXIST = new ImageIcon(Toolkit.getDefaultToolkit().createImage(imageProducer));
    }

    public static class SelectedImageFilter extends RGBImageFilter {
        private final float[] filter;

        public SelectedImageFilter(float[] filter) {
            this.filter = filter;
            // Filter's operation doesn't depend on the pixel's location, so IndexColorModels can be filtered directly.
            canFilterIndexColorModel = true;
        }

        @Override
        public int filterRGB(int x, int y, int argb) {
            int r = (int) (((argb >> 16) & 0xff) * filter[0]);
            int g = (int) (((argb >> 8) & 0xff) * filter[1]);
            int b = (int) (((argb) & 0xff) * filter[2]);
            return (argb & 0xff000000) | (r << 16) | (g << 8) | (b);
        }
    }

    public static ViewButton buildKoSelectionButton(final View2d currentView) {

        return new ViewButton(new ShowPopup() {

            @Override
            public void showPopup(Component invoker, int x, int y) {
                Collection<KOSpecialElement> koElements = DicomModel.getKoSpecialElements(currentView.getSeries());

                if (koElements == null || koElements.size() == 0) {
                    return;
                }

                Object selectedKO = currentView.getActionValue(ActionW.KEY_OBJECT.cmd());
                if (selectedKO == null) {
                    selectedKO = ActionState.NONE;
                }

                List<Object> koElementListWithNone = new ArrayList<Object>(koElements.size() + 1);
                koElementListWithNone.add(ActionState.NONE);
                koElementListWithNone.addAll(koElements);

                Object koFilter = currentView.getActionValue(ActionW.KO_FILTER.cmd());

                JPopupMenu popupMenu = new JPopupMenu();
                ButtonGroup groupButtons = new ButtonGroup();

                popupMenu.add(new TitleMenuItem(ActionW.KEY_OBJECT.getTitle(), popupMenu.getInsets()));
                popupMenu.addSeparator();

                for (final Object obj : koElementListWithNone) {
                    final RadioMenuItem menuItem = new RadioMenuItem(obj.toString(), null, obj, obj == selectedKO);

                    menuItem.addActionListener(new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            if (e.getSource() instanceof RadioMenuItem) {
                                RadioMenuItem item = (RadioMenuItem) e.getSource();
                                currentView.setKeyObjectSelection(item.getUserObject());
                            }
                        }
                    });
                    groupButtons.add(menuItem);
                    popupMenu.add(menuItem);
                }

                final JCheckBoxMenuItem menuItem =
                    new JCheckBoxMenuItem(ActionW.KO_FILTER.getTitle(), JMVUtils.getNULLtoFalse(koFilter));

                menuItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (e.getSource() instanceof JCheckBoxMenuItem) {
                            JCheckBoxMenuItem item = (JCheckBoxMenuItem) e.getSource();
                            currentView.setKeyObjectSelectionFilterState(item.isSelected());
                        }
                    }
                });

                popupMenu.addSeparator();
                popupMenu.add(menuItem);

                popupMenu.show(invoker, x, y);
            }
        }, View2d.KO_ICON);
    }

    public static KOViewButton buildKoStarButton(final View2d currentView) {

        return new KOViewButton(new ShowPopup() {

            /**
             * Test if current studyInstanceUID is referenced in the selected KEY_OBJECT of this currentView. If not,
             * search if there is a more suitable new KEY_OBJECT element. Ask the user for any watch dog case.
             */
            @Override
            public void showPopup(Component invoker, int x, int y) {

                MediaSeries<DicomImageElement> currentDicomSeries = currentView.getSeries();
                DicomImageElement currentImage = currentView.getImage();

                String currentStudyInstanceUID = (String) currentImage.getTagValue(TagW.StudyInstanceUID);
                String currentSeriesInstanceUID = (String) currentImage.getTagValue(TagW.SeriesInstanceUID);
                String currentSOPInstanceUID = (String) currentImage.getTagValue(TagW.SOPInstanceUID);
                String currentSOPClassUID = (String) currentImage.getTagValue(TagW.SOPClassUID);

                DicomModel dicomModel = (DicomModel) currentDicomSeries.getTagValue(TagW.ExplorerModel);

                KOSpecialElement selectedKO =
                    (currentView.getActionValue(ActionW.KEY_OBJECT.cmd()) instanceof KOSpecialElement)
                        ? (KOSpecialElement) currentView.getActionValue(ActionW.KEY_OBJECT.cmd()) : null;

                // null is Default, it involves creating a new dicom KO and use it as the new selection
                KOSpecialElement dicomKO = null;

                // Is there a KO selected ?
                if (selectedKO != null) {

                    // Is it a new created dicom KO ?
                    if (selectedKO.getMediaReader().isWritableDicom()) {

                        // Does this selected KO refers to this studyUID or is it empty?
                        if (selectedKO.getReferencedStudyInstanceUIDSet().contains(currentStudyInstanceUID)
                            || selectedKO.getReferencedSOPInstanceUIDSet().size() == 0) {

                            // continue with the selected KO
                            dicomKO = selectedKO;

                        } else {
                            // Ask the user whether it's better to use this selected KO with a new
                            // study reference or to create a new KO for this study

                            String message = "Be aware that selected KO doesn't have any reference on this study.\n";
                            Object[] options = { "Use it anyway", "Create a new KeyObject" };

                            int response =
                                JOptionPane.showOptionDialog(invoker, message, "Key Object Selection",
                                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);

                            if (response == 0) {
                                dicomKO = selectedKO;
                            } else if (response == JOptionPane.CLOSED_OPTION) {
                                return;
                            }
                        }

                    } else {
                        // Ask the user whether it's better to create a new KO from a copy of the selected
                        // one or to create a new KO for this study

                        String message = "Be aware that selected KO is Read Only.\n";
                        Object[] options = { "Create a new KeyObject from a copy", "Create an empty new KeyObject" };

                        int response =
                            JOptionPane.showOptionDialog(invoker, message, "Key Object Selection",
                                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);

                        if (response == 0) {
                            dicomKO = selectedKO;
                        } else if (response == JOptionPane.CLOSED_OPTION) {
                            return;
                        }
                    }
                } else {

                    // Is there any new created dicom KO for this study or any empty one?
                    Collection<KOSpecialElement> kos = DicomModel.getKoSpecialElements(currentDicomSeries);
                    if (kos != null) {
                        for (KOSpecialElement koElement : kos) {
                            if (koElement.getMediaReader().isWritableDicom()) {
                                if (koElement.getReferencedStudyInstanceUIDSet().contains(currentStudyInstanceUID)
                                    || koElement.getReferencedSOPInstanceUIDSet().size() == 0) {
                                    dicomKO = koElement;
                                    break;
                                }
                            }
                        }
                    }

                    if (dicomKO != null) {
                        // Ask the user whether it's better to switch to the most recent new created KO with a
                        // reference to this study or to create a new KO for this study

                        String message = "No KeyObject is selected but at least one is available.\n";
                        Object[] options = { "Switch to the most recent KeyObject", "Create an empty new KeyObject" };

                        int response =
                            JOptionPane.showOptionDialog(invoker, message, "Key Object Selection",
                                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);

                        if (response == 1) {
                            dicomKO = null;
                        } else if (response == JOptionPane.CLOSED_OPTION) {
                            return;
                        }
                    }
                }

                if (dicomKO == null || !dicomKO.getMediaReader().isWritableDicom()) {

                    DicomObject newDicomKO = null;

                    String message = "Set a description for the new KeyObject Selection";
                    String description =
                        (String) JOptionPane.showInputDialog(invoker, message, "Key Object Selection",
                            JOptionPane.INFORMATION_MESSAGE, null, null, "new KO selection");

                    if (dicomKO == null) {
                        String patientID = (String) currentImage.getTagValue(TagW.PatientID);
                        String patientName = (String) currentImage.getTagValue(TagW.PatientName);
                        Date patientBirthdate = (Date) currentImage.getTagValue(TagW.PatientBirthDate);

                        newDicomKO =
                            DicomMediaUtils.createDicomKeyObject(patientID, patientName, patientBirthdate,
                                currentStudyInstanceUID, description);
                    } else {
                        // create a new dicom KO from the selected one

                        // TODO should remove from the current procedure evidence any SOPInstanceUID references outside
                        // the scope of the current seriesInstanceUID or at least give the choice to the user

                        newDicomKO =
                            DicomMediaUtils.createDicomKeyObject(dicomKO.getMediaReader().getDicomObject(),
                                currentStudyInstanceUID, description);
                    }

                    new LoadDicomObjects(dicomModel, newDicomKO).addSelectionAndnotify(); // executed in the EDT

                    // Find the new KOSpecialElement just loaded and set it as default for this view
                    for (KOSpecialElement ko : DicomModel.getKoSpecialElements(currentDicomSeries)) {
                        if (ko.getMediaReader().getDicomObject().equals(newDicomKO)) {
                            dicomKO = ko;
                            break;
                        }
                    }

                    // TODO if dicomKOCandidate is a copy just remove any SopInstanceUID outside currentSeriesUID
                }

                dicomKO.toggleKeyObjectReference(currentStudyInstanceUID, currentSeriesInstanceUID,
                    currentSOPInstanceUID, currentSOPClassUID);

                if (selectedKO != dicomKO) {
                    currentView.setKeyObjectSelection(dicomKO);
                    selectedKO = dicomKO;
                }

                currentView.updateKOSelectionChange();

                // Fire an event since any view in the View2dContainner may have its KO selected state changed
                // dicomModel.firePropertyChange(new ObservableEvent(ObservableEvent.BasicAction.Update, currentView,
                // null, selectedKO));
            }
        });
    }

    // /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("serial")
    public static class KOViewButton extends ViewButton {

        protected eState state = eState.UNSELECTED;

        enum eState {
            UNSELECTED, EXIST, SELECTED
        }

        public KOViewButton(ShowPopup popup) {
            super(popup, KO_STAR_ICON);
        }

        public eState getState() {
            return state;
        }

        public void setState(eState state) {
            this.state = state;
        }

        @Override
        public Icon getIcon() {
            switch (state) {
                case UNSELECTED:
                    return KO_STAR_ICON;
                case EXIST:
                    return KO_STAR_ICON_EXIST;
                case SELECTED:
                    return KO_STAR_ICON_SELECTED;
            }
            return null;
        }
    }

}