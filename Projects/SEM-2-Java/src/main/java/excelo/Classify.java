package excelo;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import configs.MySQLConnect;
import excelo.DataBaseManager;

public class Classify extends JFrame {
    private String tableName;
    private String fileName;

    private JTable dataTable;
    private JComboBox<String> identityComboBox;
    private JComboBox<String> numericComboBox;
    private JTextField topNField;
    private JTextField bottomPercentField;
    private JButton classifyButton;
    private JPanel tablePanel;

    public Classify(String tableName, String fileName) {
        this.tableName = tableName;
        this.fileName = fileName;

        setTitle("Classify Data - " + fileName);
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        initComponents();
        loadDataFromDatabase();
    }

    private void initComponents() {
        dataTable = new JTable();

        // Apply your table styling here
        dataTable.setFont(new Font("Helvetica Neue", Font.PLAIN, 14));
        dataTable.setRowHeight(35);
        dataTable.setSelectionBackground(new Color(100, 150, 255)); // Example color for selection
        dataTable.setSelectionForeground(Color.WHITE);
        dataTable.setGridColor(new Color(200, 200, 200));
        dataTable.setShowHorizontalLines(true); // Enable horizontal lines
        dataTable.setShowVerticalLines(true);   // Enable vertical lines
        dataTable.setIntercellSpacing(new Dimension(0, 0));

        JScrollPane scrollPane = new JScrollPane(dataTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        // Create tablePanel and add JScrollPane
        tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBackground(new Color(240, 240, 240)); // Light gray background color for the panel
        tablePanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        tablePanel.add(scrollPane, BorderLayout.CENTER);

        identityComboBox = new JComboBox<>();
        numericComboBox = new JComboBox<>();
        topNField = new JTextField(5);
        bottomPercentField = new JTextField(5);
        classifyButton = new JButton("Classify");

        // Style the classify button
        styleButton(classifyButton, new Color(0, 122, 255)); // Example color

        JPanel controlPanel = new JPanel();
        controlPanel.add(new JLabel("Identity Column:"));
        controlPanel.add(identityComboBox);
        controlPanel.add(new JLabel("Numeric Column:"));
        controlPanel.add(numericComboBox);
        controlPanel.add(new JLabel("Top N:"));
        controlPanel.add(topNField);
        controlPanel.add(new JLabel("Bottom N%:"));
        controlPanel.add(bottomPercentField);

        JPanel bottomPanel = new JPanel();
        bottomPanel.add(classifyButton);

        // Adding components
        add(controlPanel, BorderLayout.CENTER);
        add(tablePanel, BorderLayout.NORTH);
        add(bottomPanel, BorderLayout.SOUTH);

        classifyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                classifyTopBottomN();
            }
        });
    }

    private void loadDataFromDatabase() {
        try (Connection conn = MySQLConnect.getConnection()) {
            String cleanTableName = tableName.replace(".xlsx", "");

            String query = "SELECT * FROM " + cleanTableName;
            PreparedStatement stmt = conn.prepareStatement(query);
            ResultSet rs = stmt.executeQuery();

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            DefaultTableModel model = new DefaultTableModel();

            for (int i = 1; i <= columnCount; i++) {
                model.addColumn(meta.getColumnName(i));
                identityComboBox.addItem(meta.getColumnName(i));
                numericComboBox.addItem(meta.getColumnName(i));
            }

            while (rs.next()) {
                Object[] row = new Object[columnCount];
                for (int i = 0; i < columnCount; i++) {
                    row[i] = rs.getObject(i + 1);
                }
                model.addRow(row);
            }
            dataTable.setModel(model);
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading data: " + e.getMessage());
        }
    }

    private void classifyTopBottomN() {
        String idCol = (String) identityComboBox.getSelectedItem();
        String numCol = (String) numericComboBox.getSelectedItem();
        int topN;
        double bottomPercentage;

        try {
            topN = Integer.parseInt(topNField.getText());
            bottomPercentage = Double.parseDouble(bottomPercentField.getText());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Please enter valid numbers for Top N and Bottom N%.");
            return;
        }

        if (topN <= 0) {
            JOptionPane.showMessageDialog(this, "Top N must be greater than 0.");
            return;
        }

        DefaultTableModel model = (DefaultTableModel) dataTable.getModel();
        List<RowData> rowDataList = new ArrayList<>();

        for (int i = 0; i < model.getRowCount(); i++) {
            Object val = model.getValueAt(i, model.findColumn(numCol));
            double numVal;
            try {
                numVal = Double.parseDouble(val.toString());
            } catch (Exception e) {
                continue;
            }
            rowDataList.add(new RowData(i, numVal));
        }

        int rowCount = rowDataList.size();

        if (topN > rowCount) {
            JOptionPane.showMessageDialog(this, "Top N cannot be greater than the total number of rows.");
            return;
        }

        rowDataList.sort(Comparator.comparingDouble(r -> r.value));

        List<Integer> topIndexes = rowDataList.subList(rowCount - topN, rowCount).stream().map(r -> r.rowIndex).toList();

        if (bottomPercentage < 0 || bottomPercentage > 100) {
            JOptionPane.showMessageDialog(this, "Please enter a valid percentage for Bottom N% (0-100).");
            return;
        }

        int bottomCount = (int) Math.ceil((bottomPercentage / 100.0) * rowCount);
        List<Integer> bottomIndexes = rowDataList.subList(0, bottomCount).stream().map(r -> r.rowIndex).toList();

        highlightRows(topIndexes, bottomIndexes);
    }

    private void highlightRows(List<Integer> topRows, List<Integer> bottomRows) {
        dataTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (topRows.contains(row)) {
                    c.setBackground(Color.GREEN); // Highlight top rows in green
                } else if (bottomRows.contains(row)) {
                    c.setBackground(Color.PINK); // Highlight bottom rows in pink
                } else {
                    c.setBackground(Color.WHITE); // Default background color for other rows
                }
                return c;
            }
        });
        dataTable.repaint();
    }

    // Method to style buttons
    private void styleButton(JButton button, Color color) {
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Helvetica Neue", Font.BOLD, 16));
        button.setPreferredSize(new Dimension(200, 50));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBorderPainted(false);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
    }

    private static class RowData {
        int rowIndex;
        double value;

        public RowData(int rowIndex, double value) {
            this.rowIndex = rowIndex;
            this.value = value;
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (UnsupportedLookAndFeelException e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            new Classify("your_table_name", "your_file.xlsx").setVisible(true);
        });
    }
}
