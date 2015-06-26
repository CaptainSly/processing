/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2013-15 The Processing Foundation
  Copyright (c) 2011-12 Ben Fry and Casey Reas

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License version 2
  as published by the Free Software Foundation.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along
  with this program; if not, write to the Free Software Foundation, Inc.
  59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package processing.app.contrib;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Date;
import java.text.DateFormat;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.StyleSheet;

import processing.app.Base;
import processing.app.Language;
import processing.app.ui.Editor;
import processing.app.ui.Toolkit;


// TODO clean up accessors (too many cases of several de-references for basic tasks
// TODO hyperlink listener seems far too complicated for what it does,
//      and why have a 'null' version rather than detecting whether selected or not
// TODO don't add/remove listeners for install/remove/undo based on function,
//      just keep track of current behavior and call that. too many things can go wrong.
// TODO get rid of huge actionPerformed() blocks with anonymous classes,
//      just make handleInstall(), etc methods and a single actionPerformed
//      for the button that calls the necessary behavior (see prev note)
// TODO switch to the built-in fonts (available from Toolkit) rather than verdana et al

/**
 * Panel that expands and gives a brief overview of a library when clicked.
 */
class ContributionPanel extends JPanel {
  static public final String REMOVE_RESTART_MESSAGE =
    String.format("<i>%s</i>", Language.text("contrib.messages.remove_restart"));

  static public final String INSTALL_RESTART_MESSAGE =
    String.format("<i>%s</i>", Language.text("contrib.messages.install_restart"));

  static public final String UPDATE_RESTART_MESSAGE =
    String.format("<i>%s</i>", Language.text("contrib.messages.update_restart"));

  static public final String PROGRESS_BAR_CONSTRAINT = "Install/Remove Progress Bar Panel";

  static public final String BUTTON_CONSTRAINT = "Install/Remove Button Panel";

  static public final String INCOMPATIBILITY_BLUR = "This contribution is not compatible with "
    + "the current revision of Processing";

  private final ContributionListPanel listPanel;
  private final ContributionListing contribListing = ContributionListing.getInstance();

  static private final int BUTTON_WIDTH = 100;

  /**
   * Should only be set through setContribution(),
   * otherwise UI components will not be updated.
   */
  private Contribution contrib;

  private boolean alreadySelected;
  private boolean enableHyperlinks;
  private HyperlinkListener conditionalHyperlinkOpener;
  private JTextPane descriptionPane;
  private JLabel notificationLabel;
  private JButton updateButton;
  private JProgressBar installProgressBar;
  private JButton installRemoveButton;
  private JPopupMenu contextMenu;
  private JMenuItem openFolder;
  private JPanel barButtonCardPane;

  private ActionListener removeActionListener;
  private ActionListener installActionListener;
  private ActionListener undoActionListener;

  private boolean isUpdateInProgress;
  private boolean isInstallInProgress;
  private boolean isRemoveInProgress;


  ContributionPanel(ContributionListPanel contributionListPanel) {
    listPanel = contributionListPanel;
    barButtonCardPane = new JPanel();

    enableHyperlinks = false;
    alreadySelected = false;
    conditionalHyperlinkOpener = new HyperlinkListener() {
      public void hyperlinkUpdate(HyperlinkEvent e) {
        if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
          if (enableHyperlinks) {
            if (e.getURL() != null) {
              Base.openURL(e.getURL().toString());
            }
          }
        }
      }
    };

    installActionListener = new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        listPanel.contributionTab.status.clear();
        isInstallInProgress = true;
        ((CardLayout) barButtonCardPane.getLayout()).show(barButtonCardPane, PROGRESS_BAR_CONSTRAINT);
        if (contrib instanceof AvailableContribution) {
          installContribution((AvailableContribution) contrib);
          contribListing.replaceContribution(contrib, contrib);
        }
      }
    };

    undoActionListener = new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        listPanel.contributionTab.status.clear();
        if (contrib instanceof LocalContribution) {
          LocalContribution installed = (LocalContribution) contrib;
          installed.setDeletionFlag(false);
          contribListing.replaceContribution(contrib, contrib);  // ??
          Iterator<Contribution> contribsListIter = contribListing.allContributions.iterator();
          boolean toBeRestarted = false;
          while (contribsListIter.hasNext()) {
            Contribution contribElement = contribsListIter.next();
            if (contrib.getType().equals(contribElement.getType())) {
              if (contribElement.isDeletionFlagged() ||
                contribElement.isUpdateFlagged()) {
                toBeRestarted = !toBeRestarted;
                break;
              }
            }
          }
          listPanel.contributionTab.restartButton.setVisible(toBeRestarted);
        }
      }
    };

    removeActionListener = new ActionListener() {
      public void actionPerformed(ActionEvent arg) {
        listPanel.contributionTab.status.clear();
        if (contrib.isInstalled() && contrib instanceof LocalContribution) {
          isRemoveInProgress = true;
          ((CardLayout) barButtonCardPane.getLayout()).show(barButtonCardPane, PROGRESS_BAR_CONSTRAINT);
          updateButton.setEnabled(false);
          installRemoveButton.setEnabled(false);
          installProgressBar.setVisible(true);
          installProgressBar.setIndeterminate(true);

          ContribProgressBar monitor = new ContribProgressBar(installProgressBar) {
            public void finishedAction() {
              // Finished uninstalling the library
              resetInstallProgressBarState();
              isRemoveInProgress = false;
              installRemoveButton.setEnabled(true);

              reorganizePaneComponents();
              setSelected(true); // Needed for smooth working. Dunno why, though...
            }

            public void cancel() {
              super.cancel();
              resetInstallProgressBarState();
              isRemoveInProgress = false;
              installRemoveButton.setEnabled(true);

              reorganizePaneComponents();
              setSelected(true);

              ContributionTab contributionTab = listPanel.contributionTab;
              boolean isModeActive = false;
              if (contrib.getType() == ContributionType.MODE) {
                ModeContribution m = (ModeContribution) contrib;
                // TODO there's gotta be a cleaner way to do this accessor
                for (Editor e : contributionTab.editor.getBase().getEditors()) {
                //Iterator<Editor> iter = listPanel.contribManager.editor.getBase().getEditors().iterator();
                //while (iter.hasNext()) {
                  //Editor e = iter.next();
                  if (e.getMode().equals(m.getMode())) {
                    isModeActive = true;
                    break;
                  }
                }
              }
              if (isModeActive) {
                updateButton.setEnabled(true);
              } else {
                contributionTab.restartButton.setVisible(true);
              }
            }
          };
          ContributionTab contributionTab = listPanel.contributionTab;
          LocalContribution localContrib = (LocalContribution) contrib;
          localContrib.removeContribution(contributionTab.editor, monitor, contributionTab.status);
        }
      }
    };

    contextMenu = new JPopupMenu();
    openFolder = new JMenuItem("Open Folder");
    openFolder.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        if (contrib instanceof LocalContribution) {
          File folder = ((LocalContribution) contrib).getFolder();
          Base.openFolder(folder);
        }
      }
    });

    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

    addPaneComponents();

    setBackground(listPanel.getBackground());
    setOpaque(true);
    setSelected(false);

    setExpandListener(this, new MouseAdapter() {
      public void mousePressed(MouseEvent e) {
        if (contrib.isCompatible(Base.getRevision())) {
          listPanel.setSelectedPanel(ContributionPanel.this);
        } else {
          final String msg = contrib.getName() +
            " is not compatible with this version of Processing";
          listPanel.contributionTab.status.setErrorMessage(msg);
        }
      }
    });
  }


  /**
   * Create the widgets for the header panel which is visible when the
   * library panel is not clicked.
   */
  private void addPaneComponents() {
    setLayout(new BorderLayout());

    descriptionPane = new JTextPane();
    descriptionPane.setInheritsPopupMenu(true);
    descriptionPane.setEditable(false);  // why would this ever be true?
    Insets margin = descriptionPane.getMargin();
    margin.bottom = 0;
    descriptionPane.setMargin(margin);
    descriptionPane.setContentType("text/html");
    setTextStyle(descriptionPane);
    descriptionPane.setOpaque(false);
    if (UIManager.getLookAndFeel().getID().equals("Nimbus")) {
      descriptionPane.setBackground(new Color(0, 0, 0, 0));
    }
//    stripTextSelectionListeners(descriptionBlock);

    descriptionPane.setBorder(new EmptyBorder(4, 7, 7, 7));
    descriptionPane.setHighlighter(null);
    add(descriptionPane, BorderLayout.CENTER);

    JPanel updateBox = new JPanel();  //new BoxLayout(filterPanel, BoxLayout.X_AXIS)
    updateBox.setLayout(new BorderLayout());

    notificationLabel = new JLabel();
    notificationLabel.setInheritsPopupMenu(true);
    notificationLabel.setVisible(false);
    notificationLabel.setOpaque(false);
    // not needed after changing to JLabel
//    notificationBlock.setContentType("text/html");
//    notificationBlock.setHighlighter(null);
//    setTextStyle(notificationBlock);
    notificationLabel.setFont(new Font("Verdana", Font.ITALIC, 10));
//    stripTextSelectionListeners(notificationBlock);

    updateButton = new JButton("Update");
    updateButton.setInheritsPopupMenu(true);
    Dimension updateButtonDimensions = updateButton.getPreferredSize();
    updateButtonDimensions.width = BUTTON_WIDTH;
    updateButton.setMinimumSize(updateButtonDimensions);
    updateButton.setPreferredSize(updateButtonDimensions);
    updateButton.setOpaque(false);
    updateButton.setVisible(false);

    updateButton.addActionListener(new ActionListener() {

      public void actionPerformed(ActionEvent e) {
        listPanel.contributionTab.status.clear();
        isUpdateInProgress = true;
        if (contrib.getType().requiresRestart()) {
          installRemoveButton.setEnabled(false);
          installProgressBar.setVisible(true);
          installProgressBar.setIndeterminate(true);

          ContribProgressBar progress = new ContribProgressBar(installProgressBar) {
            public void finishedAction() {
              // Finished uninstalling the library
              resetInstallProgressBarState();
              updateButton.setEnabled(false);
              AvailableContribution ad =
                contribListing.getAvailableContribution(contrib);
              String url = ad.link;
              installContribution(ad, url);
            }

            @Override
            public void cancel() {
              super.cancel();
              resetInstallProgressBarState();
              listPanel.contributionTab.status.setMessage("");
              isUpdateInProgress = false;
              installRemoveButton.setEnabled(true);
              if (contrib.isDeletionFlagged()) {
                ((LocalContribution)contrib).setUpdateFlag(true);
                ((LocalContribution)contrib).setDeletionFlag(false);
                contribListing.replaceContribution(contrib,contrib);
              }

              boolean isModeActive = false;
              if (contrib.getType() == ContributionType.MODE) {
                ModeContribution m = (ModeContribution) contrib;
                //Iterator<Editor> iter = listPanel.contribManager.editor.getBase().getEditors().iterator();
                //while (iter.hasNext()) {
                // TODO there's gotta be a cleaner way to do this accessor
                Base base = listPanel.contributionTab.editor.getBase();
                for (Editor e : base.getEditors()) {
                  //Editor e = iter.next();
                  if (e.getMode().equals(m.getMode())) {
                    isModeActive = true;
                    break;
                  }
                }
              }
              if (isModeActive) {
                updateButton.setEnabled(true);
              } else {
                listPanel.contributionTab.restartButton.setVisible(true);
              }
            }
          };
          ((LocalContribution) contrib)
            .removeContribution(listPanel.contributionTab.editor,
                                progress, listPanel.contributionTab.status);
        } else {
          updateButton.setEnabled(false);
          installRemoveButton.setEnabled(false);
          AvailableContribution ad = contribListing.getAvailableContribution(contrib);
          String url = ad.link;
          installContribution(ad, url);
        }
      }
    });
//      add(updateButton, c);
//    }
    updateBox.add(updateButton, BorderLayout.EAST);
    updateBox.add(notificationLabel, BorderLayout.WEST);
    updateBox.setBorder(new EmptyBorder(4, 7, 7, 7));
    updateBox.setOpaque(false);
    add(updateBox, BorderLayout.SOUTH);

//  }
//
//
//  private void addProgressBarAndButton() {

//    Box statusBox = Box.createVerticalBox();
//    GridBagConstraints c = new GridBagConstraints();
//    c.gridx = 4;
//    c.gridy = 0;
//    c.weighty = 1;
//    c.gridheight = 3;
//    c.fill = GridBagConstraints.VERTICAL;
//    c.anchor = GridBagConstraints.NORTH;
    JPanel rightPane = new JPanel();
    rightPane.setInheritsPopupMenu(true);
    rightPane.setOpaque(false);
    rightPane.setLayout(new BoxLayout(rightPane, BoxLayout.Y_AXIS));
    rightPane.setMinimumSize(new Dimension(ContributionPanel.BUTTON_WIDTH, 1));
//    add(rightPane, c);
//    statusBox.add(rightPane);
    add(rightPane, BorderLayout.EAST);

    barButtonCardPane.setLayout(new CardLayout());
    barButtonCardPane.setInheritsPopupMenu(true);
    barButtonCardPane.setOpaque(false);
    barButtonCardPane.setMinimumSize(new Dimension(ContributionPanel.BUTTON_WIDTH, 1));

    installProgressBar = new JProgressBar();
    installProgressBar.setInheritsPopupMenu(true);
    installProgressBar.setStringPainted(true);
    resetInstallProgressBarState();
    Dimension d = installProgressBar.getPreferredSize();
    d.width = ContributionPanel.BUTTON_WIDTH;
    installProgressBar.setPreferredSize(d);
    installProgressBar.setMaximumSize(d);
    installProgressBar.setMinimumSize(d);
    installProgressBar.setOpaque(false);
    installProgressBar.setAlignmentX(CENTER_ALIGNMENT);

    installRemoveButton = new JButton(" ");
    installRemoveButton.setInheritsPopupMenu(true);

    Dimension installButtonDimensions = installRemoveButton.getPreferredSize();
    installButtonDimensions.width = ContributionPanel.BUTTON_WIDTH;
    installRemoveButton.setPreferredSize(installButtonDimensions);
    installRemoveButton.setMaximumSize(installButtonDimensions);
    installRemoveButton.setMinimumSize(installButtonDimensions);
    installRemoveButton.setOpaque(false);
    installRemoveButton.setAlignmentX(CENTER_ALIGNMENT);

    JPanel barPane = new JPanel();
    barPane.setOpaque(false);
    barPane.add(installProgressBar);

    JPanel buttonPane = new JPanel();
    buttonPane.setOpaque(false);
    buttonPane.add(installRemoveButton);

    barButtonCardPane.add(buttonPane, BUTTON_CONSTRAINT);
    barButtonCardPane.add(barPane, PROGRESS_BAR_CONSTRAINT);

    ((CardLayout) barButtonCardPane.getLayout()).show(barButtonCardPane, BUTTON_CONSTRAINT);

    rightPane.add(barButtonCardPane);

    // Set the minimum size of this pane to be the sum of the height of the
    // progress bar and install button
    d = installProgressBar.getPreferredSize();
    Dimension d2 = installRemoveButton.getPreferredSize();
    d.width = ContributionPanel.BUTTON_WIDTH;
    d.height = d2.height;//d.height+d2.height;
    rightPane.setMinimumSize(d);
    rightPane.setPreferredSize(d);
  }


  private void reorganizePaneComponents() {
    BorderLayout layout = (BorderLayout) this.getLayout();
    remove(layout.getLayoutComponent(BorderLayout.SOUTH));
    remove(layout.getLayoutComponent(BorderLayout.EAST));

    JPanel updateBox = new JPanel();
    updateBox.setLayout(new BorderLayout());
    updateBox.setInheritsPopupMenu(true);
    updateBox.add(notificationLabel, BorderLayout.WEST);
    updateBox.setBorder(new EmptyBorder(4, 7, 7, 7));
    updateBox.setOpaque(false);
    add(updateBox, BorderLayout.SOUTH);

    JPanel rightPane = new JPanel();
    rightPane.setInheritsPopupMenu(true);
    rightPane.setOpaque(false);
    rightPane.setLayout(new BoxLayout(rightPane, BoxLayout.Y_AXIS));
    rightPane.setMinimumSize(new Dimension(ContributionPanel.BUTTON_WIDTH, 1));
    add(rightPane, BorderLayout.EAST);


    if (updateButton.isVisible() && !isRemoveInProgress && !contrib.isDeletionFlagged()) {
      JPanel updateRemovePanel = new JPanel();
      updateRemovePanel.setLayout(new FlowLayout());
      updateRemovePanel.setOpaque(false);
      updateRemovePanel.add(updateButton);
      updateRemovePanel.setInheritsPopupMenu(true);
      updateRemovePanel.add(installRemoveButton);
      updateBox.add(updateRemovePanel, BorderLayout.EAST);

      JPanel barPane = new JPanel();
      barPane.setOpaque(false);
      barPane.setInheritsPopupMenu(true);
      barPane.add(installProgressBar);
      rightPane.add(barPane);

      if (isUpdateInProgress)
        ((CardLayout) barButtonCardPane.getLayout()).show(barButtonCardPane, PROGRESS_BAR_CONSTRAINT);

    }
    else {
      updateBox.add(updateButton, BorderLayout.EAST);
      barButtonCardPane.removeAll();

      JPanel barPane = new JPanel();
      barPane.setOpaque(false);
      barPane.setInheritsPopupMenu(true);
      barPane.add(installProgressBar);

      JPanel buttonPane = new JPanel();
      buttonPane.setOpaque(false);
      buttonPane.setInheritsPopupMenu(true);
      buttonPane.add(installRemoveButton);

      barButtonCardPane.add(buttonPane, BUTTON_CONSTRAINT);
      barButtonCardPane.add(barPane, PROGRESS_BAR_CONSTRAINT);
      if (isInstallInProgress || isRemoveInProgress || isUpdateInProgress)
        ((CardLayout) barButtonCardPane.getLayout()).show(barButtonCardPane, PROGRESS_BAR_CONSTRAINT);
      else
        ((CardLayout) barButtonCardPane.getLayout()).show(barButtonCardPane, BUTTON_CONSTRAINT);

      rightPane.add(barButtonCardPane);
    }

    Dimension d = installProgressBar.getPreferredSize();
    Dimension d2 = installRemoveButton.getPreferredSize();
    d.width = ContributionPanel.BUTTON_WIDTH;
    d.height = Math.max(d.height,d2.height);
    rightPane.setMinimumSize(d);
    rightPane.setPreferredSize(d);
  }


  private void setExpandListener(Component component,
                                 MouseListener expandListener) {
    if (component instanceof JButton) {
      // This will confuse the button, causing it to stick on OS X
      // https://github.com/processing/processing/issues/3172
      return;
    }
    component.addMouseListener(expandListener);
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) {
        setExpandListener(child, expandListener);
      }
    }
  }


  private void blurContributionPanel(Component component) {
    component.setFocusable(false);
    component.setEnabled(false);
    if (component instanceof JComponent)
      ((JComponent) component).setToolTipText(INCOMPATIBILITY_BLUR);
    if (component instanceof Container) {
      for (Component child : ((Container) component).getComponents()) {
        blurContributionPanel(child);
      }
    }
  }


  public void setContribution(Contribution contrib) {
    this.contrib = contrib;

    if (contrib.isSpecial()) {
      ImageIcon processingIcon = Toolkit.getLibIcon("icons/pde-48.png");
      JLabel iconLabel = new JLabel(processingIcon);
      iconLabel.setBorder(new EmptyBorder(4, 7, 7, 7));
      iconLabel.setVerticalAlignment(SwingConstants.TOP);
      add(iconLabel, BorderLayout.WEST);
    }

    StringBuilder description = new StringBuilder();
    description.append("<html><body><b>");
    if (contrib.getUrl() == null) {
      description.append(contrib.getName());
    } else {
      description.append("<a href=\"" + contrib.getUrl() + "\">" + contrib.getName() + "</a>");
    }
    description.append("</b>");
    String authorList = contrib.getAuthorList();
    if (authorList != null && !authorList.isEmpty()) {
      description.append(" by ");
      description.append(toHtmlLinks(contrib.getAuthorList()));
    }
    description.append("<br/>");

    if (contrib.isDeletionFlagged()) {
      description.append(REMOVE_RESTART_MESSAGE);
    } else if (contrib.isRestartFlagged()) {
      description.append(INSTALL_RESTART_MESSAGE);
    } else if (contrib.isUpdateFlagged()) {
      description.append(UPDATE_RESTART_MESSAGE);
    } else {
      String sentence = contrib.getSentence();
      if (sentence == null || sentence.isEmpty()) {
        sentence = String.format("<i>%s</i>", Language.text("contrib.errors.description_unavailable"));
      } else {
        sentence = sanitizeHtmlTags(sentence);
        sentence = toHtmlLinks(sentence);
      }
      description.append(sentence);
    }

    String version = contrib.getPrettyVersion();

    // TODO this has no place here, we shouldn't be cleaning up contrib
    // information in the f*king GUI.
    if (version != null && !version.isEmpty()) {
      description.append("<br/>");
      if (version.toLowerCase().startsWith("build")) // For Python mode
        description.append("v"
            + version.substring(5, version.indexOf(',')).trim());
      else if (version.toLowerCase().startsWith("v")) // For ketai library
        description.append(version);
      else
        description.append("v" + version);
    }

    long lastUpdatedUTC = contrib.getLastUpdated();
    if (lastUpdatedUTC != 0) {
      DateFormat dateFormatter = DateFormat.getDateInstance(DateFormat.MEDIUM);
      Date lastUpdatedDate = new Date(lastUpdatedUTC);
      if (version != null && !version.isEmpty())
        description.append(", ");
      description.append("Last Updated on " + dateFormatter.format(lastUpdatedDate));
    }

    description.append("</body></html>");
    descriptionPane.setText(description.toString());

    if (contribListing.hasUpdates(contrib)) {
      StringBuilder versionText = new StringBuilder();
      versionText.append("<html><body><i>");
      if (contrib.isUpdateFlagged() || contrib.isDeletionFlagged()) {
        // Already marked for deletion, see requiresRestart() notes below.
        // versionText.append("To finish an update, reinstall this contribution after restarting.");
        ;
      } else {
        String latestVersion = contribListing.getLatestVersion(contrib);
        if (latestVersion != null) {
          versionText.append("New version (" + latestVersion + ") available.");
        } else {
          versionText.append("New version available.");
        }
      }
      versionText.append("</i></body></html>");
      notificationLabel.setText(versionText.toString());
      notificationLabel.setVisible(true);
    } else {
      notificationLabel.setText("");
      notificationLabel.setVisible(false);
    }

    updateButton.setEnabled(true);
    if (contrib != null) {
      updateButton.setVisible((contribListing.hasUpdates(contrib) && !contrib.isUpdateFlagged() && !contrib.isDeletionFlagged()) || isUpdateInProgress);
    }

    installRemoveButton.removeActionListener(installActionListener);
    installRemoveButton.removeActionListener(removeActionListener);
    installRemoveButton.removeActionListener(undoActionListener);

    if (contrib.isDeletionFlagged()) {
      installRemoveButton.addActionListener(undoActionListener);
      installRemoveButton.setText(Language.text("contrib.undo"));
    } else if (contrib.isInstalled()) {
      installRemoveButton.addActionListener(removeActionListener);
      installRemoveButton.setText(Language.text("contrib.remove"));
      installRemoveButton.setVisible(true);
      installRemoveButton.setEnabled(!contrib.isUpdateFlagged());
      reorganizePaneComponents();
    } else {
      installRemoveButton.addActionListener(installActionListener);
      installRemoveButton.setText(Language.text("contrib.install"));
    }

    contextMenu.removeAll();

    if (contrib.isInstalled()) {
      contextMenu.add(openFolder);
      setComponentPopupMenu(contextMenu);
    } else {
      setComponentPopupMenu(null);
    }

    if (!contrib.isCompatible(Base.getRevision())) {
      blurContributionPanel(this);
    }
  }

  private void installContribution(AvailableContribution info) {
    if (info.link == null) {
      listPanel.contributionTab.status.setErrorMessage(Language.interpolate("contrib.unsupported_operating_system", info.getType()));
    } else {
      installContribution(info, info.link);
    }
  }


  private void installContribution(AvailableContribution ad, String url) {
    installRemoveButton.setEnabled(false);

    try {
      URL downloadUrl = new URL(url);
      installProgressBar.setVisible(true);

      ContribProgressBar downloadProgress = new ContribProgressBar(installProgressBar) {
        public void finishedAction() {
          // Finished downloading library
        }

        public void cancel() {
          // Finished installing library
          resetInstallProgressBarState();
          installRemoveButton.setEnabled(!contrib.isUpdateFlagged());

          ((CardLayout) barButtonCardPane.getLayout()).show(barButtonCardPane, BUTTON_CONSTRAINT);
          isInstallInProgress = false;
          if(isUpdateInProgress)
            isUpdateInProgress = !isUpdateInProgress;
          updateButton.setVisible(contribListing.hasUpdates(contrib) && !contrib.isUpdateFlagged());
          setSelected(true);
        }
      };

      ContribProgressBar installProgress = new ContribProgressBar(installProgressBar) {
        public void finishedAction() {
          // Finished installing library
          resetInstallProgressBarState();
          installRemoveButton.setEnabled(!contrib.isUpdateFlagged());

          if (isError()) {
            listPanel.contributionTab.status.setErrorMessage(Language.text("contrib.download_error"));
          }
          ((CardLayout) barButtonCardPane.getLayout()).show(barButtonCardPane, BUTTON_CONSTRAINT);
          isInstallInProgress = false;
          if(isUpdateInProgress)
            isUpdateInProgress = !isUpdateInProgress;
          updateButton.setVisible(contribListing.hasUpdates(contrib) && !contrib.isUpdateFlagged());
          setSelected(true);
        }

        public void cancel() {
          finishedAction();
        }
      };

      ContributionManager.downloadAndInstall(listPanel.contributionTab.editor,
                                             downloadUrl, ad,
                                             downloadProgress, installProgress,
                                             listPanel.contributionTab.status);

    } catch (MalformedURLException e) {
      Base.showWarning(Language.text("contrib.errors.install_failed"),
                       Language.text("contrib.errors.malformed_url"), e);
      // not sure why we'd re-enable the button if it had an error...
//      installRemoveButton.setEnabled(true);
    }
  }


  // This doesn't actually seem to work?
  /*
  static void stripTextSelectionListeners(JEditorPane editorPane) {
    for (MouseListener listener : editorPane.getMouseListeners()) {
      String className = listener.getClass().getName();
      if (className.endsWith("MutableCaretEvent") ||
          className.endsWith("DragListener") ||
          className.endsWith("BasicCaret")) {
        editorPane.removeMouseListener(listener);
      }
    }
  }
  */


  protected void resetInstallProgressBarState() {
    installProgressBar.setString(Language.text("contrib.progress.starting"));
    installProgressBar.setIndeterminate(false);
    installProgressBar.setValue(0);
    installProgressBar.setVisible(false);
  }


  /**
   * Should be called whenever this component is selected (clicked on)
   * or unselected, even if it is already selected.
   */
  public void setSelected(boolean isSelected) {
    // Only enable hyperlinks if this component is already selected.
    // Why? Because otherwise if the user happened to click on what is
    // now a hyperlink, it will be opened as the mouse is released.
    enableHyperlinks = alreadySelected;

    if (contrib != null) {
      updateButton.setVisible((contribListing.hasUpdates(contrib) && !contrib.isUpdateFlagged() && !contrib.isDeletionFlagged()) || isUpdateInProgress);
      updateButton.setEnabled(!contribListing.hasListDownloadFailed());
    }
    installRemoveButton.setVisible(isSelected() || installRemoveButton.getText().equals(Language.text("contrib.remove")) || isUpdateInProgress);
    installRemoveButton.setEnabled(installRemoveButton.getText().equals(Language.text("contrib.remove")) ||!contribListing.hasListDownloadFailed());
    reorganizePaneComponents();

    descriptionPane.removeHyperlinkListener(ContributionListPanel.nullHyperlinkListener);
    descriptionPane.removeHyperlinkListener(conditionalHyperlinkOpener);
    if (isSelected()) {
      descriptionPane.addHyperlinkListener(conditionalHyperlinkOpener);
//      descriptionPane.setEditable(false);
    } else {
      descriptionPane.addHyperlinkListener(ContributionListPanel.nullHyperlinkListener);
//      descriptionPane.setEditable(true);
    }

    // Update style of hyperlinks
    setSelectionStyle(descriptionPane, isSelected());

    alreadySelected = isSelected();
  }


  public boolean isSelected() {
    return listPanel.getSelectedPanel() == this;
  }


  public void setForeground(Color fg) {
    super.setForeground(fg);

    if (contrib != null) {
      boolean installed = contrib.isInstalled();
      setForegroundStyle(descriptionPane, installed, isSelected());
    }
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  static String sanitizeHtmlTags(String stringIn) {
    stringIn = stringIn.replaceAll("<", "&lt;");
    stringIn = stringIn.replaceAll(">", "&gt;");
    return stringIn;
  }


  /**
   * This has a [link](http://example.com/) in [it](http://example.org/).
   *
   * Becomes...
   *
   * This has a <a href="http://example.com/">link</a> in <a
   * href="http://example.org/">it</a>.
   */
  static String toHtmlLinks(String stringIn) {
    Pattern p = Pattern.compile("\\[(.*?)\\]\\((.*?)\\)");
    Matcher m = p.matcher(stringIn);

    StringBuilder sb = new StringBuilder();

    int start = 0;
    while (m.find(start)) {
      sb.append(stringIn.substring(start, m.start()));

      String text = m.group(1);
      String url = m.group(2);

      sb.append("<a href=\"");
      sb.append(url);
      sb.append("\">");
      sb.append(text);
      sb.append("</a>");

      start = m.end();
    }
    sb.append(stringIn.substring(start));
    return sb.toString();
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  /**
   * Sets coloring based on whether installed or not;
   * also makes ugly blue HTML links into the specified color (black).
   */
  static void setForegroundStyle(JTextPane textPane, boolean installed, boolean selected) {
    Document doc = textPane.getDocument();
    if (doc instanceof HTMLDocument) {
      HTMLDocument html = (HTMLDocument) doc;
      StyleSheet stylesheet = html.getStyleSheet();

      String c = (installed && !selected) ? "#555555" : "#000000";  // slightly grayed when installed
//      String c = "#000000";  // just make them both black
      stylesheet.addRule("body { color:" + c + "; }");
      stylesheet.addRule("a { color:" + c + "; }");
    }
  }


  static void setTextStyle(JTextPane textPane) {
    Document doc = textPane.getDocument();
    if (doc instanceof HTMLDocument) {
      HTMLDocument html = (HTMLDocument) doc;
      StyleSheet stylesheet = html.getStyleSheet();
      stylesheet.addRule("body { " +
                         "  margin: 0; padding: 0;" +
                         "  font-family: Verdana, Geneva, Arial, Helvetica, sans-serif;" +
                         "  font-size: 100%;" + "font-size: 0.95em; " +
                         "}");
    }
  }


  static void setSelectionStyle(JTextPane textPane, boolean selected) {
    Document doc = textPane.getDocument();
    if (doc instanceof HTMLDocument) {
      HTMLDocument html = (HTMLDocument) doc;
      StyleSheet styleSheet = html.getStyleSheet();
      if (selected) {
        styleSheet.addRule("a { text-decoration:underline } ");
      } else {
        styleSheet.addRule("a { text-decoration:none }");
      }
    }
  }
}
