package com.ratemywine.corpustograph;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class CorpusToGraphFrame extends JFrame {
    private final JTextField directoryField = new JTextField(35);
    private final JComboBox<SimilarityModel> modelCombo = new JComboBox<>(SimilarityModel.values());
    private final JSlider speedSlider = new JSlider(1, 300, 100);
    private final JTextArea detailsArea = new JTextArea(10, 25);
    private final GraphPanel graphPanel = new GraphPanel();

    private final CorpusParser parser = new CorpusParser();
    private final SimilarityCalculator calculator = new SimilarityCalculator();

    private List<DocumentData> documents = Collections.emptyList();
    private GraphSimulation simulation;

    public CorpusToGraphFrame() {
        super("CorpusToGraph - BM25 / TF-IDF Visualizer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 700));
        setLayout(new BorderLayout(8, 8));

        add(buildTopControls(), BorderLayout.NORTH);
        add(graphPanel, BorderLayout.CENTER);
        add(buildRightPanel(), BorderLayout.EAST);

        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);

        speedSlider.setMajorTickSpacing(50);
        speedSlider.setPaintTicks(true);

        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildTopControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton browse = new JButton("Choisir dossier");
        JButton compute = new JButton("Calculer");

        browse.addActionListener(e -> chooseDirectory());
        compute.addActionListener(e -> computeGraph());

        panel.add(browse);
        panel.add(directoryField);
        panel.add(new JLabel("Modèle:"));
        panel.add(modelCombo);
        panel.add(new JLabel("Vitesse:"));
        panel.add(speedSlider);
        panel.add(compute);
        return panel;
    }

    private JPanel buildRightPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(320, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Document sélectionné"));
        panel.add(new JScrollPane(detailsArea), BorderLayout.CENTER);
        return panel;
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            directoryField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void computeGraph() {
        Path path = Path.of(directoryField.getText().trim());
        try {
            documents = parser.parseDirectory(path);
            if (documents.size() < 2) {
                throw new IllegalArgumentException("Le dossier doit contenir au moins 2 documents valides (.txt/.md/.html).");
            }
            SimilarityModel model = (SimilarityModel) modelCombo.getSelectedItem();
            double[][] sim = calculator.computeSimilarityMatrix(documents, model);
            simulation = new GraphSimulation(documents, sim, graphPanel.getWidth(), graphPanel.getHeight());
            graphPanel.repaint();
            detailsArea.setText("Graph prêt: " + documents.size() + " documents.\nCliquez une particule pour voir le résumé.");
        } catch (IOException | IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }

    private class GraphPanel extends JPanel {
        private final Timer timer;

        private GraphPanel() {
            setBackground(new Color(16, 20, 29));
            setPreferredSize(new Dimension(700, 600));

            timer = new Timer(16, e -> {
                if (simulation != null) {
                    double speed = speedSlider.getValue() / 100.0;
                    simulation.step(0.12, speed);
                    repaint();
                }
            });
            timer.start();

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (simulation == null) {
                        return;
                    }
                    GraphSimulation.Particle p = simulation.findParticle(e.getPoint());
                    if (p != null) {
                        showDocumentInfo(p.index);
                    }
                }
            });

            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (simulation != null) {
                        simulation.resize(getWidth(), getHeight());
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (simulation == null) {
                drawCenteredMessage((Graphics2D) g, "Choisissez un dossier puis cliquez sur Calculer");
                return;
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            double[][] sim = simulation.similarity();
            List<GraphSimulation.Particle> particles = simulation.particles();

            for (int i = 0; i < particles.size(); i++) {
                for (int j = i + 1; j < particles.size(); j++) {
                    double s = sim[i][j];
                    if (s < 0.12) {
                        continue;
                    }
                    int alpha = Math.min(180, (int) (s * 180));
                    g2.setColor(new Color(90, 150, 230, alpha));
                    g2.setStroke(new BasicStroke((float) (0.4 + s * 2.8)));
                    GraphSimulation.Particle a = particles.get(i);
                    GraphSimulation.Particle b = particles.get(j);
                    g2.drawLine((int) a.x, (int) a.y, (int) b.x, (int) b.y);
                }
            }

            for (GraphSimulation.Particle p : particles) {
                DocumentData d = documents.get(p.index);
                int size = (int) Math.round(p.radius * 2);
                g2.setColor(new Color(255, 177, 66));
                g2.fillOval((int) (p.x - p.radius), (int) (p.y - p.radius), size, size);
                g2.setColor(Color.BLACK);
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
                g2.drawString(trimLabel(d.title(), 14), (int) (p.x - p.radius), (int) (p.y - p.radius - 4));
            }
        }

        private void drawCenteredMessage(Graphics2D g2, String message) {
            g2.setColor(Color.LIGHT_GRAY);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 16f));
            int width = g2.getFontMetrics().stringWidth(message);
            g2.drawString(message, (getWidth() - width) / 2, getHeight() / 2);
        }
    }

    private void showDocumentInfo(int index) {
        DocumentData doc = documents.get(index);
        String content = "Titre: " + doc.title() + "\n"
                + "Fichier: " + doc.path() + "\n"
                + "Taille (tokens): " + doc.tokenCount() + "\n\n"
                + "Résumé:\n" + doc.summary();
        detailsArea.setText(content);
    }

    private String trimLabel(String label, int maxLength) {
        if (label.length() <= maxLength) {
            return label;
        }
        return label.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    public static void start() {
        SwingUtilities.invokeLater(() -> new CorpusToGraphFrame().setVisible(true));
    }
}
