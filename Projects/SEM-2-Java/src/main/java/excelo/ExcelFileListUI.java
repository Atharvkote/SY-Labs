package excelo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.sql.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import configs.MySQLConnect;
import utils.ImageUtil;

public class ExcelFileListUI extends JFrame {
    private JTable fileTable;
    private DefaultTableModel tableModel;
    private JLabel LogoTitle;

    public ExcelFileListUI() {
        setTitle("Dashboard");
        setSize(1000, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Colors (unchanged from original)
        Color backgroundColor = new Color(240, 248, 255);
        Color sidebarColor = new Color(25, 25, 112);
        Color buttonColor = new Color(70, 130, 180);
        Color tableRowColor = new Color(255, 255, 255);
        Color tableAltRowColor = new Color(230, 240, 255);
        Color tableSelectionColor = new Color(100, 149, 237);

        getContentPane().setBackground(backgroundColor);

        // Sidebar (Full Height)
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(sidebarColor);
        sidebar.setPreferredSize(new Dimension(200, getHeight())); // Full height

        JButton importButton = new JButton("Import Excel");
        styleSidebarButton(importButton, buttonColor);
        importButton.addActionListener(e -> redirectToAppUI());
        sidebar.add(Box.createVerticalStrut(20));
        sidebar.add(importButton);
        sidebar.add(Box.createVerticalGlue());

        add(sidebar, BorderLayout.WEST);

        // Table setup
        tableModel = new DefaultTableModel();
        fileTable = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                if (isRowSelected(row)) {
                    component.setBackground(tableSelectionColor);
                } else {
                    component.setBackground(row % 2 == 0 ? tableRowColor : tableAltRowColor);
                }
                return component;
            }
        };
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.setFont(new Font("Arial", Font.PLAIN, 14));
        fileTable.setRowHeight(30);
        fileTable.setShowGrid(false);
        fileTable.setIntercellSpacing(new Dimension(0, 0));
        JScrollPane scrollPane = new JScrollPane(fileTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        tableModel.addColumn("Upload Excel Files");

        // Search Bar
        JTextField searchField = new JTextField(25);
        searchField.setFont(new Font("Arial", Font.PLAIN, 14));
        searchField.setPreferredSize(new Dimension(250, 35));
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(tableSelectionColor, 2),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterTable(); }
            public void removeUpdate(DocumentEvent e) { filterTable(); }
            public void changedUpdate(DocumentEvent e) { filterTable(); }

            private void filterTable() {
                String query = searchField.getText().toLowerCase();
                TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
                fileTable.setRowSorter(sorter);
                sorter.setRowFilter(RowFilter.regexFilter("(?i)" + query));
            }
        });

        // ==== HEADER PANEL (Logo and Search Bar) ====
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(backgroundColor);
        headerPanel.setPreferredSize(new Dimension(getWidth(), 80));

        // Left: Logo + Title
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        leftPanel.setBackground(backgroundColor);

        ImageIcon scaledIcon = ImageUtil.getHighQualityScaledIcon("src/images/Excelo.png", 40, 40);
        JLabel logoLabel = new JLabel(scaledIcon);
        logoLabel.setPreferredSize(new Dimension(50, 50));

        LogoTitle = new JLabel(
                "<html>" +
                        "<span style='font-family:Helvetica Neue, Helvetica, Arial, sans-serif; font-size:24px; font-weight:900;'>Excelo</span> " +
                        "<span style='font-size:16px;'><i>- Fast! , Quick! , Easy!</i></span>" +
                        "</html>"
        );
        LogoTitle.setFont(new Font("Helvetica Neue", Font.BOLD, 16));
        LogoTitle.setForeground(buttonColor);

        leftPanel.add(logoLabel);
        leftPanel.add(LogoTitle);

        // Right: Search Bar with Icon
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        searchPanel.setBackground(backgroundColor);
        JLabel searchIcon = new JLabel(ImageUtil.getHighQualityScaledIcon("src/images/search.svg", 50, 50)); // Change to actual path of your search icon
        searchPanel.add(searchIcon);

        searchPanel.add(searchField);

        headerPanel.add(leftPanel, BorderLayout.WEST);
        headerPanel.add(searchPanel, BorderLayout.EAST);

        // Add headerPanel below the sidebar and above the table
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBackground(backgroundColor);
        contentPanel.add(headerPanel, BorderLayout.NORTH);
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        // Add the content panel in the center of the layout
        add(contentPanel, BorderLayout.CENTER);

        // Table click listener
        fileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = fileTable.getSelectedRow();
                if (row != -1) {
                    String tableName = (String) fileTable.getValueAt(row, 0);
                    redirectToAppUIWithTable(tableName);
                }
            }
        });

        listTablesFromDatabase();
    }

    private void styleSidebarButton(JButton button, Color color) {
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Arial", Font.BOLD, 16));
        button.setPreferredSize(new Dimension(180, 50));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBorderPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    private void listTablesFromDatabase() {
        try (Connection conn = MySQLConnect.getConnection()) {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SHOW TABLES");
            while (rs.next()) {
                tableModel.addRow(new Object[]{rs.getString(1)});
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading tables: " + ex.getMessage());
        }
    }

    private void redirectToAppUIWithTable(String tableName) {
        this.setVisible(false);
        AppUI appUI = new AppUI();
        appUI.loadDataFromDatabase(tableName);
        appUI.setVisible(true);
        this.dispose();
    }

    private void redirectToAppUI() {
        this.setVisible(false);
        new AppUI().setVisible(true);
        this.dispose();
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatLightLaf()); // Material-inspired UI theme
        } catch (Exception e) {
            e.printStackTrace();
        }
        SwingUtilities.invokeLater(() -> new ExcelFileListUI().setVisible(true));
    }
}
